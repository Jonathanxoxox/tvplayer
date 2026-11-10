package tv.own.owntv.features.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.computeContentHash
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.live.GuideKeys
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.parser.EpgDetails
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.launcher.apps.LauncherSystem
import java.util.concurrent.ConcurrentHashMap

/**
 * MagentaTV's guide as the stored EPG for the Spotlight's channels (Jonathan, 10 Nov 2026: "MagentaTV is the
 * main source for the channels it covers"; scope narrowed the same day to the channels Home shows, so the box
 * stays light and the provider's EPG serves every other channel as before). The provider's short EPG costs one
 * slow request per channel; here the guide of the Home row, arte, the film channels and the football channels is fetched once in the
 * background ([MagentaGuide.playbills]) and written to `epg_programmes` under keys of its own ([KEY_PREFIX] + the
 * channel's [MagentaNames.key], source [SOURCE_ID]). [GuideKeys.override] then sends every covered channel
 * there, so the player info, the guide grid, the channel list and Home answer from the database, at once and
 * offline, and the Spotlight and the channel itself always show the same programme.
 *
 * - **What is synced**: the channels Home hands in through [prioritise] (kept on the device, so a cold start can
 *   run a due round before Home is there), then the playlist's football channels that MagentaTV carries
 *   ([MagentaNames.isFootballChannel]: Sky Sport, Sky Bundesliga, DAZN), found by listing the profile's live
 *   channels once per round. That listing is the only use of the playlist; no other channel is synced. Without
 *   either, a round touches neither network nor stored rows.
 * - **Covered**: the keys with rows written that the check ([MagentaMatch]) has not rejected. Kept on the device
 *   ([PREFS]) and read in the constructor, so the very first guide read after a cold start already uses the rows
 *   the last run left in the database. The override answers from memory and never blocks.
 * - **A round** every [SYNC_EVERY_MS] (wall clock), after the start has settled and with a network, and on
 *   waking from standby when the last one is that old. A different set of channels from Home makes a round due
 *   for the new keys alone while the last full one is still fresh. The window is [BACK_MS] back to [AHEAD_MS]
 *   ahead: today and tomorrow, so a box that was off overnight (or for a day) still has its guide in the database
 *   the moment it starts, before any network. [BACK_MS] is three hours (rows are kept four hours back): a film
 *   that started two hours ago is still in the list, which the Spotlight's "on now" needs. The channels are fetched and written [WRITE_BATCH] at a time (one delete and one upsert per batch:
 *   every write to the table re-triggers the app's guide observers), and each batch is covered at once, so Home
 *   fills after the first batch. Covered keys that are no longer on Home are dropped and their rows deleted. A
 *   failed round keeps what is stored and is tried again after a doubling wait.
 * - **One download for two readers**: each fetched chunk's raw lists are handed on through [onPlaybills], so the
 *   Spotlight's picks ([SpotlightPicks]) come out of the same download instead of a second one. [refetch] makes
 *   the next round fetch given keys again although they are covered (their pick flags changed).
 * - **Verification**: after a round, covered channels whose check is still open ask the provider directly what is
 *   on now and compare it with MagentaTV's ([MagentaMatch]). A channel the two guides disagree on is dropped from
 *   the covered set and left alone for [RECHECK_REJECTED_MS].
 */
class MagentaEpg(
    context: Context,
    private val magenta: Lazy<MagentaGuide>,
    private val epgDao: Lazy<EpgDao>,
    private val liveEpgReader: Lazy<LiveEpgReader>,
    private val settings: Lazy<SettingsRepository>,
    private val sourceDao: Lazy<SourceDao>,
    private val channelDao: Lazy<ChannelDao>,
    private val customize: Lazy<tv.own.owntv.core.customize.CustomizationStore>,
    private val connectivity: Lazy<ConnectivityObserver>,
) {
    /** What the start log shows. */
    data class Status(val lastSyncAt: Long = 0L, val covered: Int = 0, val rejected: Int = 0, val running: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keys with stored rows, replaced as a whole (never changed in place), so the override reads it unlocked. */
    @Volatile private var covered: Set<String> = emptySet()
    private val _coveredKeys = MutableStateFlow<Set<String>>(emptySet())
    /** The covered keys as a flow, for readers that must follow changes ([covered] itself is for the override). */
    val coveredKeys: StateFlow<Set<String>> = _coveredKeys.asStateFlow()

    /**
     * Called once per fetched chunk with the raw lists of the keys that answered (key = the channel's
     * [MagentaNames.key]) and when they were fetched. A throwing hook does not break the round.
     */
    @Volatile var onPlaybills: ((lists: Map<String, JSONArray>, fetchedAt: Long) -> Unit)? = null

    /** Keys the next round fetches again although covered; added to its fetch list and then cleared. */
    private val refetchKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val refetchTick = MutableStateFlow(0)
    private var rejected: Map<String, Long> = emptyMap()
    private var verdicts: Map<String, List<MagentaMatch.Verdict>> = emptyMap()
    private var lastSyncAt = 0L

    /**
     * Channel id → its name and guide key ([NONE] for a channel the guide does not cover). Cleared with [covered];
     * the name is kept beside the answer, so an id that a playlist sync gave to another channel is asked afresh.
     */
    private val memo = ConcurrentHashMap<Long, Pair<String, String>>()

    /** What Home wants synced: the channels' keys (also kept on the device) and the channels, for the check. */
    private data class Wanted(val keys: List<String>, val channels: List<ChannelEntity> = emptyList())
    private val wanted = MutableStateFlow(Wanted(emptyList()))
    /** The keys the last finished round was made for. */
    private var planned: Set<String> = emptySet()
    /** The open checks were tried for what is wanted now (a round does them itself). */
    @Volatile private var verifyTried = false
    /** The playlist's football channels as the last round found them, for the check. */
    private var footballChannels: List<ChannelEntity> = emptyList()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        // The one synchronous read: a small file, so the override is right before the first guide read.
        runCatching {
            covered = prefs.getStringSet(COVERED, null).orEmpty().toSet()
            rejected = MagentaEpgRules.decodeRejected(prefs.getStringSet(REJECTED, null).orEmpty())
            verdicts = MagentaEpgRules.decodeVerdicts(prefs.getStringSet(VERDICTS, null).orEmpty())
            lastSyncAt = prefs.getLong(LAST_SYNC, 0L)
            planned = prefs.getString(PLANNED, null).orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
            wanted.value = Wanted(prefs.getString(PRIORITY, null).orEmpty().split('\n').filter { it.isNotEmpty() })
        }
        _coveredKeys.value = covered
        _status.value = Status(lastSyncAt, covered.size, rejected.size, false)
        GuideKeys.override = { ch -> keyFor(ch) }
        scope.launch { keepFresh() }
    }

    /**
     * Home's channels (the row, arte, the film channels): the only ones synced. An empty list changes nothing; a
     * different set makes a round due for the keys not yet covered.
     */
    fun prioritise(channels: List<ChannelEntity>) {
        val keys = channels.map { MagentaNames.key(it.name) }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return
        val next = Wanted(keys.take(MAX_CHANNELS), channels.distinctBy { it.id })
        if (next.keys != wanted.value.keys) {
            verifyTried = false
            runCatching { prefs.edit().putString(PRIORITY, next.keys.joinToString("\n")).apply() }
        }
        wanted.value = next
    }

    /**
     * Makes a round due that fetches exactly [keys] again, covered or not (their pick flags changed, so the lists
     * must be read again with the new flags). Keys MagentaTV does not carry are skipped by the round.
     */
    fun refetch(keys: Collection<String>) {
        if (keys.isEmpty()) return
        refetchKeys.addAll(keys)
        refetchTick.update { it + 1 }
    }

    /** The key to read [ch]'s guide under, null when MagentaTV's rows do not cover it. O(1) after the first call. */
    private fun keyFor(ch: ChannelEntity): String? {
        memo[ch.id]?.let { (name, key) -> if (name == ch.name) return key.ifEmpty { null } }
        val snapshot = covered
        val k = MagentaNames.key(ch.name)
        val answer = if (k.isNotEmpty() && k in snapshot) KEY_PREFIX + k else NONE
        // Not remembered when the covered set changed meanwhile: the answer may be stale.
        if (covered === snapshot) memo[ch.id] = ch.name to answer
        return answer.ifEmpty { null }
    }

    private suspend fun keepFresh() {
        // Never during the box's first seconds.
        tv.own.owntv.StartupGate.awaitSettled()
        var retry = 0
        var force = false
        while (true) {
            val want = wanted.value
            val wake = LauncherSystem.wake.value
            val age = System.currentTimeMillis() - lastSyncAt
            val every = if (covered.isEmpty()) EMPTY_RECHECK_MS else SYNC_EVERY_MS
            val full = force || age >= every
            val tick = refetchTick.value
            val partial = !full && (want.keys.toSet() != planned || refetchKeys.isNotEmpty())
            if (!full && !partial) {
                if (!verifyTried && (want.channels.isNotEmpty() || footballChannels.isNotEmpty()) && covered.isNotEmpty()) {
                    connectivity.value.isOnline.first { it }
                    runCatching { verify(want.keys + (covered - want.keys.toSet()), want.channels + footballChannels) }
                    verifyTried = true
                    _status.update { it.copy(running = false) }
                    continue
                }
                // Current: wait until due, or until the box wakes or Home asks for others.
                withTimeoutOrNull(every - age) {
                    combine(LauncherSystem.wake, wanted, refetchTick) { w, x, t -> Triple(w, x, t) }
                        .first { it != Triple(wake, want, tick) }
                }
                continue
            }
            connectivity.value.isOnline.first { it }
            val ok = runCatching { round(want, full) }.getOrDefault(false)
            _status.update { it.copy(running = false) }
            if (ok) {
                retry = 0
                force = false
                continue
            }
            force = true
            val wait = (RETRY_MS shl retry).coerceAtMost(SYNC_EVERY_MS)
            retry = (retry + 1).coerceAtMost(RETRY_STEPS)
            withTimeoutOrNull(wait) { LauncherSystem.wake.first { it != wake } }
        }
    }

    /**
     * One round for [want]: every key when [full], else only those not covered yet. False when the guide could
     * not be asked or not one channel answered; the stored rows stay.
     */
    private suspend fun round(want: Wanted, full: Boolean): Boolean {
        _status.update { it.copy(running = true) }
        val guide = magenta.value
        // The playlist only in a full round (every six hours), and only to find its football channels: it can hold
        // thousands of channels. A partial round (Home asked for others) keeps the football keys already covered.
        val football = if (full) {
            val profile = activeProfileSources(settings.value, sourceDao.value).first { it.profileId >= 0 }
            channelDao.value.allForSources(profile.sourceIdsFor(MediaType.LIVE), CHANNEL_LIMIT)
                .filter { MagentaNames.isFootballChannel(MagentaNames.key(it.name)) }
                .distinctBy { MagentaNames.key(it.name) }
                .also { footballChannels = it }
        } else footballChannels
        val footballKeys = if (full) football.map { MagentaNames.key(it.name) }
        else covered.filter { MagentaNames.isFootballChannel(it) }
        val index = if (want.keys.isEmpty() && footballKeys.isEmpty()) emptyMap() else guide.guideIndex() ?: return false
        val now = System.currentTimeMillis()
        val plan = MagentaEpgRules.plan(
            index = index,
            wantedKeys = want.keys,
            footballKeys = footballKeys,
            rejected = rejected,
            covered = covered,
            full = full,
            now = now,
            recheckAfterMs = RECHECK_REJECTED_MS,
            cap = MAX_CHANNELS,
            footballCap = MAX_FOOTBALL,
        )
        if (plan.forgotten.isNotEmpty()) {
            rejected = rejected - plan.forgotten
            persist()
        }
        // Keys asked for again (their pick flags changed); handed over to this round and forgotten.
        val again = refetchKeys.toList().also { refetchKeys.removeAll(it.toSet()) }.filter { it in plan.keys && it !in plan.fetch }
        var answered = 0
        for (chunk in (plan.fetch + again).chunked(WRITE_BATCH)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val lists = guide.playbills(chunk.map { index.getValue(it) }, now - BACK_MS, now + AHEAD_MS)
            val rows = ArrayList<EpgProgrammeEntity>()
            val written = ArrayList<String>()
            val answeredLists = HashMap<String, JSONArray>()
            for (key in chunk) {
                val list = lists[index.getValue(key)] ?: continue
                answered++
                answeredLists[key] = list
                val own = MagentaEpgRules.rows(key, MagentaNames.programmes(list))
                if (own.isEmpty()) continue
                rows += own
                written += key
            }
            if (answeredLists.isNotEmpty()) runCatching { onPlaybills?.invoke(answeredLists, now) }
            if (written.isEmpty()) continue
            // One delete and one upsert for the whole batch, never one per channel.
            epgDao.value.deleteProgrammesForChannels(SOURCE_ID, written.map { KEY_PREFIX + it })
            epgDao.value.upsertProgrammes(rows)
            setCovered(covered + written)
        }
        if ((plan.fetch.isNotEmpty() || again.isNotEmpty()) && answered == 0) return false
        epgDao.value.pruneOutsideWindow(SOURCE_ID, now - PRUNE_BACK_MS, now + PRUNE_AHEAD_MS)
        // Channels that left Home: no longer covered, their rows gone.
        val drop = covered - plan.keys.toSet()
        if (drop.isNotEmpty()) {
            epgDao.value.deleteProgrammesForChannels(SOURCE_ID, drop.map { KEY_PREFIX + it })
            setCovered(covered - drop)
        }
        if (full) lastSyncAt = now
        planned = want.keys.toSet()
        persist()
        publish(running = true)
        verify(plan.keys, want.channels + football)
        return true
    }

    /** The provider's "on now" against MagentaTV's for a few covered channels whose check is still open. */
    private suspend fun verify(order: List<String>, channels: List<ChannelEntity>) {
        verifyTried = true
        val open = order.filter { it in covered && MagentaMatch.overall(verdicts[it].orEmpty()) == MagentaMatch.Verdict.UNKNOWN }
            .take(VERIFY_PER_ROUND)
        if (open.isEmpty()) return
        val byKey = channels.groupBy { MagentaNames.key(it.name) }
        val profileId = activeProfileSources(settings.value, sourceDao.value).first { it.profileId >= 0 }.profileId
        val cust = customize.value.observe(profileId, MediaType.LIVE).first()
        val global = settings.value.epgOffsetMinutes.first()
        for (key in open) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val channel = byKey[key]?.firstOrNull() ?: continue
            val verdict = runCatching { verdictFor(key, channel, cust, global) }.getOrDefault(MagentaMatch.Verdict.UNKNOWN)
            if (verdict != MagentaMatch.Verdict.UNKNOWN) {
                val observed = MagentaEpgRules.observe(verdicts[key].orEmpty(), verdict)
                verdicts = verdicts + (key to observed.history)
                if (observed.rejected) reject(key) else persist()
            }
            delay(VERIFY_PAUSE_MS)
        }
    }

    private suspend fun verdictFor(key: String, channel: ChannelEntity, cust: SectionCustomizations, global: Int): MagentaMatch.Verdict {
        val now = System.currentTimeMillis()
        val shift = EpgShift.minutesFor(cust, channel, global)
        // The provider's rows come back on the user's clock; the stored ones are on the feed's own clock.
        val provider = liveEpgReader.value.providerProgrammes(channel, cust, global)
        val stored = epgDao.value.nowPlaying(KEY_PREFIX + key, now - shift * 60_000L)
        return MagentaEpgRules.compare(provider, stored, now, shift)
    }

    /** Drops [key] from the covered set, remembers when, and removes its rows: its guide disagrees with the provider's. */
    private suspend fun reject(key: String) {
        rejected = rejected + (key to System.currentTimeMillis())
        verdicts = verdicts - key
        setCovered(covered - key)
        runCatching { epgDao.value.deleteProgrammesForChannels(SOURCE_ID, listOf(KEY_PREFIX + key)) }
    }

    /** A new covered set: kept, the override's memo forgotten, and the reader's cached now/next with it. */
    private fun setCovered(next: Set<String>) {
        if (next == covered) return
        covered = next
        _coveredKeys.value = next
        memo.clear()
        liveEpgReader.value.clearCache()
        persist()
        publish(running = true)
    }

    private fun publish(running: Boolean) {
        _status.value = Status(lastSyncAt, covered.size, rejected.size, running)
    }

    private fun persist() {
        runCatching {
            prefs.edit()
                .putStringSet(COVERED, covered)
                .putStringSet(REJECTED, MagentaEpgRules.encodeRejected(rejected))
                .putStringSet(VERDICTS, MagentaEpgRules.encodeVerdicts(verdicts))
                .putLong(LAST_SYNC, lastSyncAt)
                .putString(PLANNED, planned.joinToString("\n"))
                .apply()
        }
        publish(running = _status.value.running)
    }

    companion object {
        /** Reserved: user-added EPG feeds count down from -1, playlists are positive. */
        const val SOURCE_ID = -9001L
        const val KEY_PREFIX = "magenta:"
        private const val NONE = ""
        private const val PREFS = "mayo_magenta_epg"
        private const val COVERED = "covered"
        private const val REJECTED = "rejected"
        private const val VERDICTS = "verdicts"
        private const val LAST_SYNC = "last_sync"
        private const val PRIORITY = "priority"
        private const val PLANNED = "planned"

        const val BACK_MS = 3 * 60 * 60_000L
        const val AHEAD_MS = 48 * 60 * 60_000L
        const val SYNC_EVERY_MS = 6 * 60 * 60_000L
        const val MAX_CHANNELS = 100
        /** Of those, at most this many football channels. */
        const val MAX_FOOTBALL = 40
        const val WRITE_BATCH = 25
        const val VERIFY_PER_ROUND = 20
        const val RECHECK_REJECTED_MS = 7 * 24 * 60 * 60_000L

        /** Rows older / further ahead than this are pruned at the end of a round. */
        private const val PRUNE_BACK_MS = 4 * 60 * 60_000L
        private const val PRUNE_AHEAD_MS = 60 * 60 * 60_000L
        /** With nothing covered (no channel in common, say) a round is tried again after this, not in a loop. */
        private const val EMPTY_RECHECK_MS = 60 * 60_000L
        private const val CHANNEL_LIMIT = 20_000
        private const val RETRY_MS = 10_000L
        /** 10, 20, 40 s, … then every [SYNC_EVERY_MS] at the most. */
        private const val RETRY_STEPS = 10
        private const val VERIFY_PAUSE_MS = 300L
    }
}

/** The stored guide's pure rules: what a round fetches, what it writes, and what the check makes of the answers. */
internal object MagentaEpgRules {
    /**
     * What a round does: [keys] is the wanted set that MagentaTV can serve (in Home's order), [fetch] the keys to
     * fetch now, [drop] the covered keys that are no longer in [keys] (their rows go), and [forgotten] the rejected
     * keys whose time is up.
     */
    data class Plan(val keys: List<String>, val fetch: List<String>, val drop: Set<String>, val forgotten: Set<String>)

    /**
     * [wantedKeys] (Home's channels, in order) and then [footballKeys] (at most [footballCap] of them, those Home
     * does not already have), kept when MagentaTV's [index] has them and they are not rejected (a rejection older
     * than [recheckAfterMs] is forgotten), at most [cap] in all. A [full] round fetches all of them, a partial
     * one only those not yet [covered]. Covered keys outside the result are dropped.
     */
    fun plan(
        index: Map<String, String>,
        wantedKeys: List<String>,
        footballKeys: List<String>,
        rejected: Map<String, Long>,
        covered: Set<String>,
        full: Boolean,
        now: Long,
        recheckAfterMs: Long,
        cap: Int,
        footballCap: Int,
    ): Plan {
        val forgotten = rejected.filterValues { now - it >= recheckAfterMs }.keys
        val blocked = rejected.keys - forgotten
        val home = wantedKeys.distinct().filter { it in index && it !in blocked }
        val football = footballKeys.distinct().filter { it in index && it !in blocked && it !in home }.take(footballCap)
        val keys = (home + football).take(cap)
        return Plan(keys, if (full) keys else keys.filter { it !in covered }, covered - keys.toSet(), forgotten)
    }

    /** [programmes] of the guide channel [key] as rows of the stored guide ([MagentaEpg.SOURCE_ID], prefixed key). */
    fun rows(key: String, programmes: List<MagentaProgramme>): List<EpgProgrammeEntity> = programmes.map { p ->
        EpgProgrammeEntity(
            sourceId = MagentaEpg.SOURCE_ID,
            epgChannelId = MagentaEpg.KEY_PREFIX + key,
            startMs = p.startMs,
            stopMs = p.stopMs,
            title = p.title,
            description = p.description,
            categories = categories(p.genres),
            year = p.year,
            episode = p.episode,
        ).let { it.copy(contentHash = it.computeContentHash()) }
    }

    /** Distinct, at most [EpgDetails.MAX_CATEGORIES], joined by [EpgDetails.CATEGORY_SEPARATOR]; null when none. */
    fun categories(genres: List<String>): String? = genres.asSequence()
        .map { it.trim().replace(EpgDetails.CATEGORY_SEPARATOR, ' ') }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .take(EpgDetails.MAX_CATEGORIES)
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(EpgDetails.CATEGORY_SEPARATOR.toString())

    /** Verdicts kept per channel. */
    const val KEEP = 6

    data class Observed(val history: List<MagentaMatch.Verdict>, val rejected: Boolean)

    /**
     * [history] with [verdict] added, the last [KEEP] kept (UNKNOWN says nothing and is not added), and whether
     * the channel is now rejected: [MagentaMatch.overall] says MISMATCH.
     */
    fun observe(history: List<MagentaMatch.Verdict>, verdict: MagentaMatch.Verdict): Observed {
        val next = if (verdict == MagentaMatch.Verdict.UNKNOWN) history else (history + verdict).takeLast(KEEP)
        return Observed(next, MagentaMatch.overall(next) == MagentaMatch.Verdict.MISMATCH)
    }

    /**
     * The provider's programme on now ([provider], on the user's clock) against MagentaTV's [stored] one for
     * [now] minus the shift, moved by the same [shiftMinutes] onto the user's clock.
     */
    fun compare(provider: List<EpgProgrammeEntity>, stored: EpgProgrammeEntity?, now: Long, shiftMinutes: Int): MagentaMatch.Verdict {
        val theirs = provider.firstOrNull { it.startMs <= now && it.stopMs > now }
        val delta = shiftMinutes * 60_000L
        return MagentaMatch.verdict(
            magenta = stored?.let { MagentaMatch.Slot(it.title, it.startMs + delta, it.stopMs + delta) },
            provider = theirs?.let { MagentaMatch.Slot(it.title, it.startMs, it.stopMs) },
        )
    }

    // The persisted forms: "key<TAB>value" strings in a string set; keys hold no tab (ProgrammeTitles.key).
    private const val TAB = '\t'

    fun encodeRejected(m: Map<String, Long>): Set<String> = m.mapTo(HashSet()) { (k, at) -> "$k$TAB$at" }

    fun decodeRejected(s: Set<String>): Map<String, Long> = s.mapNotNull { e ->
        val at = e.substringAfterLast(TAB, "").toLongOrNull() ?: return@mapNotNull null
        e.substringBeforeLast(TAB) to at
    }.toMap()

    fun encodeVerdicts(m: Map<String, List<MagentaMatch.Verdict>>): Set<String> = m.entries
        .filter { it.value.isNotEmpty() }
        .mapTo(HashSet()) { (k, v) -> k + TAB + v.joinToString("") { if (it == MagentaMatch.Verdict.MATCH) "M" else "X" } }

    fun decodeVerdicts(s: Set<String>): Map<String, List<MagentaMatch.Verdict>> = s.mapNotNull { e ->
        val letters = e.substringAfterLast(TAB, "")
        if (TAB !in e || letters.isEmpty()) return@mapNotNull null
        e.substringBeforeLast(TAB) to letters.map { if (it == 'M') MagentaMatch.Verdict.MATCH else MagentaMatch.Verdict.MISMATCH }
    }.toMap()
}

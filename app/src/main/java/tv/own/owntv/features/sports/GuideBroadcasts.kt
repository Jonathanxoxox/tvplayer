package tv.own.owntv.features.sports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchTier
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.launcher.FootballMatcher
import tv.own.owntv.launcher.FootballScan

/**
 * A football match's channels found the way "Für dich" finds them, beside core's Find channels.
 *
 * Core reads only the stored guide, and only titles that spell a side as ESPN does. A playlist whose
 * guide comes from the provider's short EPG has nothing stored, and "Hamburger SV" is not ESPN's
 * "Hamburg SV": the match page then said "Nicht auf deinen Sendern" while "Für dich" showed the match on
 * Sky. Here every sports channel's guide is read as the Live TV list reads it, and a programme on at
 * kick-off that names the match ([FootballMatcher.Fixture]) lists its channel as a guide hit.
 */
internal object GuideBroadcasts {

    /** What a search has so far: [options], and whether every guide has answered ([done]). */
    class Progress(val options: List<WatchOption>, val done: Boolean)

    private class Snapshot(val profileId: Long, val at: Long, val channels: List<ChannelEntity>, val guides: Map<Long, EpgNowNext>)

    @Volatile private var snapshot: Snapshot? = null
    private val found = HashMap<String, List<WatchOption>>()

    /** One read at a time, for every page that asks; it runs on even when the page that started it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Deferred<Snapshot?>? = null
    /** The guides the running read has so far; null while none runs. */
    private val partial = MutableStateFlow<Snapshot?>(null)

    /**
     * The guides "Für dich" has just read (the launcher rows, every 15 minutes and on leaving the app):
     * the same channels this search reads, so a match page shows them at once instead of asking again.
     */
    fun remember(profileId: Long, channels: List<ChannelEntity>, guides: Map<Long, EpgNowNext>) {
        snapshot = Snapshot(profileId, System.currentTimeMillis(), channels, guides)
    }

    /** Opening Sports: reads the guides when the last read is older than [MAX_AGE_MS], so a match finds them ready. */
    suspend fun warm() {
        val profile = activeProfile() ?: return
        if (fresh(profile.profileId) == null) start(profile)
    }

    /**
     * [match]'s channels from the guides, as they come: at once from the last read, then, when that is
     * older than [MAX_AGE_MS], growing while the guides are read again (favourites and the big sports
     * channels first). The last [Progress] is [Progress.done].
     */
    fun find(match: Match): Flow<Progress> = channelFlow {
        val profile = if (match.sport == Sport.SOCCER) activeProfile() else null
        if (profile == null) {
            send(Progress(emptyList(), done = true))
            return@channelFlow
        }
        val fixture = FootballMatcher.fixture(match)
        val hidden = customizations(profile.profileId).hiddenItems
        // Variants share titles, and a read in progress is matched again every [FootballScan] progress step.
        val shown = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        fun options(channels: List<ChannelEntity>, guides: Map<Long, EpgNowNext>) = channels
            .filter { CustomizeKeys.channel(it) !in hidden }
            .flatMap { ch ->
                val g = guides[ch.id] ?: return@flatMap emptyList()
                (listOfNotNull(g.now, g.next) + g.upcoming).distinctBy { it.startMs }
                    .filter { p ->
                        p.startMs in (match.startMs - BEFORE_MS)..(match.startMs + AFTER_MS) && p.stopMs > match.startMs &&
                            shown.getOrPut(p.title) { fixture.shownIn(p.title) }
                    }
                    .take(1).map { WatchOption(ch, WatchTier.GUIDE, it.title) }
            }
        suspend fun done(s: Snapshot?) {
            val options = s?.let { options(it.channels, it.guides) }.orEmpty()
            synchronized(found) { found[match.key] = options }
            send(Progress(options, done = true))
        }

        fresh(profile.profileId)?.let { done(it); return@channelFlow }
        val known = snapshot?.takeIf { it.profileId == profile.profileId }
        if (known != null) send(Progress(options(known.channels, known.guides), done = false))
        val read = start(profile)
        val watch = launch {
            partial.collect { p -> if (p != null) send(Progress(options(p.channels, known?.guides.orEmpty() + p.guides), done = false)) }
        }
        val s = read.await()
        watch.cancel()
        done(s ?: known)
    }

    /** What the last [find] for [match] gave, without reading anything. */
    fun cached(match: Match): List<WatchOption> = synchronized(found) { found[match.key].orEmpty() }

    /**
     * Core's options and [guide]'s in one list, one row per channel, best tier first and, within a tier,
     * the channels the user watches first ([order]).
     */
    fun merge(core: List<WatchOption>, guide: List<WatchOption>, order: WatchedOrder = WatchedOrder.NONE): List<WatchOption> {
        val out = LinkedHashMap<Long, WatchOption>()
        (core + guide).sortedWith(compareBy({ it.tier.ordinal }, { order.rank(it.channel) })).forEach { out.putIfAbsent(it.channel.id, it) }
        return out.values.toList()
    }

    /**
     * The channels the user watches, most recently watched first, and their variants right after them
     * ("Sky Sport 3 HD (Sat)" watched puts "Sky Sport 3 HD" and its other feeds up too).
     */
    class WatchedOrder(private val byId: Map<Long, Int>, private val byFamily: Map<String, Int>) {
        fun rank(ch: ChannelEntity): Int =
            byId[ch.id]?.let { it * 2 } ?: byFamily[family(ch)]?.let { it * 2 + 1 } ?: Int.MAX_VALUE

        companion object {
            val NONE = WatchedOrder(emptyMap(), emptyMap())
        }
    }

    /** The active profile's [WatchedOrder], from its Live TV history. */
    suspend fun watchedOrder(): WatchedOrder {
        val profile = activeProfile() ?: return WatchedOrder.NONE
        val watched = runCatching { GlobalContext.get().get<ChannelDao>().recentlyWatched(profile.profileId, MAX_WATCHED).first() }
            .getOrDefault(emptyList())
        val byId = HashMap<Long, Int>()
        val byFamily = HashMap<String, Int>()
        watched.forEachIndexed { i, ch ->
            byId.putIfAbsent(ch.id, i)
            byFamily.putIfAbsent(family(ch), i)
        }
        return WatchedOrder(byId, byFamily)
    }

    private fun family(ch: ChannelEntity): String = EpgMatcher.normalizeForEpg(ch.name).ifEmpty { ch.id.toString() }

    private suspend fun activeProfile(): ActiveProfileSources? {
        val koin = GlobalContext.get()
        return withTimeoutOrNull(15_000) {
            activeProfileSources(koin.get<SettingsRepository>(), koin.get<SourceDao>()).first { it.profileId >= 0 }
        }
    }

    private suspend fun customizations(profileId: Long): SectionCustomizations =
        GlobalContext.get().get<CustomizationStore>().observe(profileId, MediaType.LIVE).first()

    private fun fresh(profileId: Long): Snapshot? =
        snapshot?.takeIf { it.profileId == profileId && System.currentTimeMillis() - it.at < MAX_AGE_MS }

    /** The running read, or a new one. */
    private fun start(profile: ActiveProfileSources): Deferred<Snapshot?> = synchronized(this) {
        running?.takeIf { it.isActive } ?: scope.async {
            try {
                runCatching { read(profile.profileId, profile.sourceIdsFor(MediaType.LIVE)) }.getOrNull()
            } finally {
                partial.value = null
            }
        }.also { running = it }
    }

    /** Every football channel's guide, read now; [partial] holds what is read so far while it runs. */
    private suspend fun read(profileId: Long, sources: List<Long>): Snapshot? {
        if (sources.isEmpty()) return null
        val koin = GlobalContext.get()
        val channelDao = koin.get<ChannelDao>()
        val epgReader = koin.get<LiveEpgReader>()
        val custom = customizations(profileId)
        val offset = koin.get<SettingsRepository>().epgOffsetMinutes.first()
        val now = System.currentTimeMillis()
        val favorites = channelDao.snapshotFavoritesManual(profileId, ContentOrderEntity.FAV_CONTEXT, sources, MAX_FAVORITES)
        val favoriteIds = favorites.map { it.id }.toSet()
        val watched = watchedOrder()
        // The likeliest first, so the first rows come in a second or two: favourites, the channels the user
        // watches (most recent first), the big sports channels, then the rest in playlist order.
        val visible = FootballScan.channels(channelDao, sources, favorites).all
            .filter { CustomizeKeys.channel(it) !in custom.hiddenItems }
        // Each key once per channel: compareBy's selectors run on every comparison, and rank() normalises the
        // name with regexes, which made this sort 18 % of the start's background CPU on a Nokia 8010.
        val channels = visible
            .map { ch -> watched.rank(ch).let { rank -> Triple(ch, tier(ch, favoriteIds, rank), rank) } }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
        val guides = FootballScan.guides(channels, onProgress = { partial.value = Snapshot(profileId, now, channels, it) }) { ch ->
            runCatching { epgReader.nowNext(ch, custom, offset) }.getOrNull()
        }
        return Snapshot(profileId, now, channels, guides).also { snapshot = it }
    }

    /**
     * Where [read] puts [ch]: 0 a favourite, 1 a channel the user watches, 2 a big sports channel by name,
     * 3 the rest. [rank] is the channel's [WatchedOrder.rank], passed in because it costs a name
     * normalisation (regexes) and the caller needs it for the order within a tier anyway.
     */
    private fun tier(ch: ChannelEntity, favoriteIds: Set<Long>, rank: Int): Int = when {
        ch.id in favoriteIds -> 0
        rank != Int.MAX_VALUE -> 1
        FootballMatcher.fold(ch.name).let { n -> FIRST.any { n.contains(it) } } -> 2
        else -> 3
    }

    /**
     * Channel-name words read first: where the football Jonathan follows mostly is. Matched against the
     * folded name ([FootballMatcher.fold]: lower case, no accents, anything but letters and digits as one
     * space), so entries are written that way: "sky sport austria", not "Sky Sport Austria".
     */
    private val FIRST = listOf(
        "sky", "dazn", "sport1", "sport 1", "magenta", "zdf", "das erste",
        // Champions League in German: Amazon's Tuesday match comes through the event channels.
        "amazon event",
        // Austria ("AT: ORF Sport+ HD" folds to "at orf sport hd").
        "orf 1", "orf sport", "servus tv", "puls 4", "laola1",
        // Switzerland.
        "blue sport", "mysports", "srf 2",
        // Denmark: Superliga and Champions League.
        "tv3", "canal 9", "6 eren", "tv 2 sport",
    )

    /** A guide read is reused this long; a short-EPG read asks the provider. */
    private const val MAX_AGE_MS = 10 * 60_000L
    private const val MAX_FAVORITES = 40
    private const val MAX_WATCHED = 100
    /** A programme starting this long before kick-off (Sky's build-up) to this long after counts. */
    private const val BEFORE_MS = 3 * 60 * 60_000L
    private const val AFTER_MS = 30 * 60_000L
}

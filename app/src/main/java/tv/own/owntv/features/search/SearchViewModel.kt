@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package tv.own.owntv.features.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.ui.stage.PlaylistMark
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.content.SearchIntent
import tv.own.owntv.core.content.SearchKind
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Cross-section search over a profile's channels, movies and series.
 *
 * The searching itself is core's [SearchReader]: the FTS expression, the hidden items and
 * categories, the kids filter and the Customize renames are the same rules on a phone as on a
 * television, so they are not written twice. What stays here is the screen's own behaviour —
 * debouncing and the recents list. A result is never played here: OK goes to it.
 */
class SearchViewModel(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val settings: SettingsRepository,
    private val searchReader: SearchReader,
    private val metadata: MetadataRepository,
    private val moodSearch: MoodSearch,
    private val voiceQuery: VoiceQuery,
    private val liveGuide: LiveGuideSearch,
) : ViewModel() {

    // Observe the active profile's sources reactively so adding/removing a playlist refreshes Search
    // immediately (was read once at startup, so a new playlist showed nothing until app restart).
    // Per-section Off flags split the id sets so an Off section never surfaces in results.
    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveProfileSources(-1L, emptyList()))

    // The guide's sweep is read while the first letters are typed, so the first results do not wait for it.
    init { viewModelScope.launch { runCatching { liveGuide.warm() } } }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    // With the playlists as a second input: a query typed before they were read (a fresh start, the
    // mic) used to stay at "nothing found" until the text changed.
    val results: StateFlow<SearchResults> = combine(_query.map { it.trim() }.debounce(300).distinctUntilChanged(), ctx) { q, c -> q to c }
        .flatMapLatest { (q, c) ->
            if (q.length < 2) {
                flowOf(SearchResults())
            } else {
                flowOf(search(q, c))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /**
     * Actor and director finds ("Tom Cruise"): the playlist's films and series of a person of that name,
     * from TMDB credits. Waits like [mood]; a title or channel name finds no person and shows nothing.
     */
    val people: StateFlow<Pair<String, MoodResults>?> = combine(_query.map { it.trim() }.debounce(800).distinctUntilChanged(), ctx) { q, c -> q to c }
        .transformLatest { (q, c) ->
            emit(null)
            if (q.length < MoodSearch.MIN_LENGTH || c.profileId < 0) return@transformLatest
            val found = moodSearch.people(q, c)
            if (found != null && !found.isEmpty) emit(q to found)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Search by description ("Stonermovie"): Jev decides whether the text describes what to watch and
     * which playlist titles fit it (see [MoodSearch]). Waits a little longer than the plain search so
     * half-typed words cost nothing; a title or channel name ends as [MoodState.Off].
     */
    val mood: StateFlow<MoodState> = combine(_query.map { it.trim() }.debounce(800).distinctUntilChanged(), ctx) { q, c -> q to c }
        .transformLatest { (q, c) ->
            if (!moodSearch.available || q.length < MoodSearch.MIN_LENGTH || c.profileId < 0) { emit(MoodState.Off); return@transformLatest }
            val cachedVod = moodSearch.cached(q)
            val cachedTv = synchronized(wishCache) { wishCache[q.lowercase()] }
            if (cachedVod != null && cachedTv != null) {
                val both = cachedVod.copy(programmes = cachedTv)
                emit(if (both.isEmpty) MoodState.Off else MoodState.Found(q, both))
                return@transformLatest
            }
            emit(MoodState.Searching(q))
            // Films and series from the playlist, and what fits on TV in the next hours, at once.
            val found = kotlinx.coroutines.coroutineScope {
                val vod = async { cachedVod ?: moodSearch.search(q, c) }
                val tv = async {
                    cachedTv ?: currentProfileId()
                        ?.let { pid -> runCatching { liveGuide.byWish(q, pid, c) }.getOrNull() }
                        .orEmpty()
                        .also { synchronized(wishCache) { wishCache[q.lowercase()] = it } }
                }
                (vod.await() ?: MoodResults()).copy(programmes = tv.await())
            }
            emit(if (found.isEmpty) MoodState.Off else MoodState.Found(q, found))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoodState.Off)

    fun setQuery(q: String) {
        if (q.trim() != _query.value.trim()) lastFocusKey = null
        _query.value = q
        if (q.isNotBlank()) _intent.value = null // typing overrides an active launcher intent
    }

    /** The mic's guesses (best first) → the text to search for: Jev picks the channel or title meant. */
    suspend fun resolveSpoken(heard: List<String>): String {
        val pid = currentProfileId() ?: return heard.first()
        return voiceQuery.resolve(heard) { q -> searchReader.search(pid, ctx.value, q) }
    }

    // --- Empty-state launcher (recent search terms + Continue / Unwatched / Channels) ---

    /** Persisted recent search terms (most-recent first). */
    val recentSearches: StateFlow<List<String>> = settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearRecentSearches() { viewModelScope.launch { settings.clearRecentSearches() } }

    /** Save the current query into recents — called when the user actually opens a result. */
    fun rememberCurrentQuery() { viewModelScope.launch { settings.addRecentSearch(_query.value) } }

    private val _intent = MutableStateFlow<SearchIntent?>(null)
    val intent: StateFlow<SearchIntent?> = _intent.asStateFlow()

    /** Selecting an intent clears any typed query so the curated list shows; null returns to the launcher. */
    fun setIntent(i: SearchIntent?) {
        _intent.value = i
        if (i != null) _query.value = ""
    }

    /** Curated results for the active empty-state intent (bounded; reuses favourites/history queries). */
    val curatedResults: StateFlow<SearchResults> = combine(_intent, ctx) { i, c -> i to c }
        .flatMapLatest { (i, c) ->
            if (i == null) flowOf(SearchResults())
            else flowOf(searchReader.curated(c.profileId, c, i))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    private suspend fun search(q: String, c: ActiveProfileSources): SearchResults {
        val pid = currentProfileId() ?: return SearchResults()
        val stored = searchReader.search(pid, c, q)
        if (c.liveSourceIds.isEmpty()) return stored
        // MagentaTV's whole guide too (see [LiveGuideSearch]): titles first, then what the genres and descriptions
        // name ("Fußball"). The stored guide's finds stay; a programme found twice is listed once.
        val guide = runCatching { liveGuide.byTitle(q, pid, c) + liveGuide.byText(q, pid, c) }.getOrDefault(emptyList())
        if (guide.isEmpty()) return stored
        val now = System.currentTimeMillis()
        val titleKey = { r: tv.own.owntv.core.content.ProgrammeSearchResult -> r.channel.id to r.programme.startMs }
        val merged = (stored.programmes + guide).distinctBy(titleKey)
        val (byTitle, byText) = merged.partition { tv.own.owntv.features.home.MagentaNames.fold(q) in tv.own.owntv.features.home.MagentaNames.fold(it.programme.title) }
        val order = compareBy<tv.own.owntv.core.content.ProgrammeSearchResult>({ it.programme.startMs > now }, { it.programme.startMs })
        return stored.copy(programmes = (byTitle.sortedWith(order) + byText.sortedWith(order)).take(PROGRAMME_LIMIT))
    }

    /** What the guide had for a described wish, per wish, for a few back-and-forths. */
    private val wishCache = object : LinkedHashMap<String, List<tv.own.owntv.core.content.ProgrammeSearchResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<tv.own.owntv.core.content.ProgrammeSearchResult>>?) = size > 20
    }

    // --- Stage page ---

    /** The filter buttons switched on; none = everything. Kept across new search text; Back clears it. */
    private val _kinds = MutableStateFlow<Set<SearchKind>>(emptySet())
    val kinds: StateFlow<Set<SearchKind>> = _kinds.asStateFlow()
    fun toggleKind(k: SearchKind) { _kinds.value = if (k in _kinds.value) _kinds.value - k else _kinds.value + k }
    fun showOnly(k: SearchKind) { _kinds.value = setOf(k) }
    fun clearKinds() { _kinds.value = emptySet() }

    /** The row a "Go to" left from ("c12", "m34", "s56"), so Back to Search lands on it again. */
    var lastFocusKey: String? = null

    /** Which playlist a row comes from, as on the browse screens; empty with one playlist. */
    val playlistMarks: StateFlow<Map<Long, PlaylistMark>> = ctx
        .map { aps -> if (aps.sources.size < 2) emptyMap() else aps.sources.withIndex().associate { (i, s) -> s.id to PlaylistMark.of(s.name, i) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Category names for the movie and series rows ("2005 · ★ 6.2 · Disney+ Movies"). */
    val categoryNames: StateFlow<Map<Long, String>> = combine(results, curatedResults, mood, people) { a, b, m, p ->
        val found = (m as? MoodState.Found)?.results ?: MoodResults()
        val person = p?.second ?: MoodResults()
        (a.movies.mapNotNull { it.categoryId } + a.series.mapNotNull { it.categoryId } +
            b.movies.mapNotNull { it.categoryId } + b.series.mapNotNull { it.categoryId } +
            found.movies.mapNotNull { it.categoryId } + found.series.mapNotNull { it.categoryId } +
            person.movies.mapNotNull { it.categoryId } + person.series.mapNotNull { it.categoryId }).toSet()
    }
        .distinctUntilChanged()
        .mapLatest { ids -> ids.mapNotNull { id -> categoryDao.getById(id)?.let { id to it.name } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val metadataMode: StateFlow<tv.own.owntv.core.metadata.MetadataMode> = settings.metadataMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tv.own.owntv.core.metadata.MetadataMode.PROVIDER_PLUS_TMDB)

    /** The focused movie or series, for the poster panel's TMDB details (backdrop, genres, cast). */
    private val _focusedVod = MutableStateFlow<Any?>(null)
    fun focusVod(item: Any?) { _focusedVod.value = item }

    /** The focused title's key ("m34" / "s56") and its TMDB details, after the same focus debounce the
     *  browse screens use, so passing over rows looks nothing up. */
    val focusedMeta: StateFlow<Pair<String, MetadataCacheEntity?>?> = _focusedVod
        .transformLatest { item ->
            val key = when (item) { is MovieEntity -> "m${item.id}"; is SeriesEntity -> "s${item.id}"; else -> null }
            if (key == null) { emit(null); return@transformLatest }
            delay(MetadataRepository.FOCUS_DEBOUNCE_MS)
            val cache = runCatching {
                when (item) { is MovieEntity -> metadata.resolveMovie(item); is SeriesEntity -> metadata.resolveSeries(item); else -> null }
            }.getOrNull()
            emit(key to cache)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }
}

/** The described-search section of the results: nothing, a "Jev is looking" line, or what fits. */
sealed interface MoodState {
    data object Off : MoodState
    data class Searching(val query: String) : MoodState
    data class Found(val query: String, val results: MoodResults) : MoodState
}

/** How many programmes "Im TV" lists. */
private const val PROGRAMME_LIMIT = 40

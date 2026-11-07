@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package tv.own.owntv.features.vod

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.customize.railCategories
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ContentOrderDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ContentOrderEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.customize.MoveTarget
import tv.own.owntv.features.live.LiveRailItem
import tv.own.owntv.ui.components.OwnTVIcon

/** The active profile and the playlists a VOD section shows, with their names. */
internal data class VodCtx(
    val profileId: Long,
    val sourceIds: List<Long>,
    val sourceNames: Map<Long, String>,
)

/** Customizations + resolved hidden-category ids, bundled so the list pipeline takes one flow. */
internal data class VodCustState(val cust: SectionCustomizations, val hiddenCats: Set<Long>)

/**
 * The category side of a VOD section (Movies or Series): the rail, the profile's customizations,
 * custom categories with "Move to…", and which category is selected or pinned. Both view models
 * hold one, so the two sections cannot drift apart.
 */
internal class VodCategories(
    private val scope: CoroutineScope,
    private val type: MediaType,
    val ctx: StateFlow<VodCtx>,
    sortMode: Flow<SettingsRepository.SortMode>,
    private val categoryDao: CategoryDao,
    contentOrderDao: ContentOrderDao,
    private val customize: CustomizationStore,
    private val customCategoryDao: CustomCategoryDao,
    profileDao: ProfileDao,
    private val userDataWriter: tv.own.owntv.core.backup.UserDataWriter,
    /** Lets the browse screen tell core's drain which category to fill first. */
    private val catalogPriority: tv.own.owntv.core.sync.CatalogPriority,
    private val currentProfileId: suspend () -> Long?,
    /** The TMDB and progress catalogs, listed above the playlist's own entries. */
    private val catalogs: StateFlow<List<VodCatalog>>,
) {

    val folderContextKeys: StateFlow<Map<Long, String>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyMap())
            else categoryDao.observe(c.sourceIds, type).map { cats ->
                cats.associateBy({ it.id }, { CustomizeKeys.category(it) })
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Contexts that actually have manual-order rows: only those folders pay the
     *  unindexable content_order join-sort; everything else stays on the plain indexed query. */
    val orderedContexts: StateFlow<Set<String>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptySet())
            else contentOrderDao.observeContextKeys(c.profileId, type).map { it.toSet() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** This profile's hide/rename/reorder customizations for the section. */
    val custom: StateFlow<SectionCustomizations> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(SectionCustomizations())
            else customize.observe(c.profileId, type)
        }
        .stateIn(scope, SharingStarted.Eagerly, SectionCustomizations())

    /** The user's custom combined categories with live member counts — the "Move to…" dialog's list. */
    val moveTargets: StateFlow<List<MoveTarget>> = combine(ctx, custom) { c, cust -> c to cust }
        .flatMapLatest { (c, cust) ->
            if (c.profileId < 0 || cust.customCategories.isEmpty()) flowOf(emptyList())
            else customCategoryDao.observeCountsByContexts(
                c.profileId,
                type,
                cust.customCategories.map { it.id },
                c.sourceIds.ifEmpty { listOf(-1L) },
            ).map { counts ->
                cust.customCategories.map { cc ->
                    MoveTarget(
                        id = cc.id,
                        displayName = cust.categoryNames[cc.id] ?: cc.name,
                        count = counts.firstOrNull { it.contextKey == cc.id }?.count ?: 0,
                    )
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The stable key of a provider folder ([null] when the folder vanished) — the Move dialog's origin. */
    fun folderKey(id: Long): String? = folderContextKeys.value[id]

    /** Creates a custom category — the Move dialog's "＋ New category…" flow. */
    fun createCustomCategory(name: String) {
        scope.launch {
            val pid = currentProfileId() ?: return@launch
            customize.createCustomCategory(pid, type, name)
        }
    }

    /**
     * Moves (or copies, [keepInOrigin]) one title into a custom category. The item is
     * appended at the category's tail (maxPosition + 1). Without [keepInOrigin] the item leaves its
     * origin: a favorite row is deleted, a custom-category membership row is deleted, and a provider
     * folder is marked in movedFromOrigin — the pager chain then drops it from that folder while
     * keeping it in All / search / recent.
     */
    fun moveToCategory(itemKey: String, itemId: Long, originKey: String, targetId: String, keepInOrigin: Boolean) {
        if (targetId == originKey) return
        scope.launch {
            val pid = currentProfileId() ?: return@launch
            customCategoryDao.appendItem(pid, type, targetId, itemId)
            if (!keepInOrigin) {
                when {
                    originKey == ContentOrderEntity.FAV_CONTEXT -> userDataWriter.removeFavorite(pid, type, itemId)
                    CustomizeKeys.isCustom(originKey) -> userDataWriter.removeCustomCategoryMember(pid, type, originKey, itemId)
                    else -> customize.setItemMovedFromOrigin(pid, type, itemKey, originKey, moved = true)
                }
            }
        }
    }

    /**
     * Category DB ids of this profile's hidden categories — so hiding a category hides its
     * titles everywhere (All, search, Home rails), not just the rail folder (mirrors Live TV).
     */
    val hiddenCategoryIds: StateFlow<Set<Long>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) {
                flowOf(emptySet())
            } else {
                combine(categoryDao.observe(c.sourceIds, type), custom, profileDao.observeById(c.profileId)) { cats, cust, profile ->
                    tv.own.owntv.core.content.AdultCategoryClassifier.hiddenCategoryIds(
                        cats,
                        cust.hiddenCategories,
                        profile?.isKids == true,
                    )
                }
            }
        }
        // Classifying every category runs off the main thread (hundreds on a big playlist).
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val custResolved: StateFlow<VodCustState> = combine(custom, hiddenCategoryIds) { c, h -> VodCustState(c, h) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), VodCustState(SectionCustomizations(), emptySet()))

    val railItems: StateFlow<List<LiveRailItem>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(DEFAULT_RAIL)
            else combine(
                categoryDao.observe(c.sourceIds, type),
                customize.observe(c.profileId, type),
                sortMode,
                profileDao.observeById(c.profileId),
                catalogs,
            ) { cats, cust, sort, profile, catalogList ->
                // A–Z also sorts the category folders (custom categories included); manually moved
                // categories stay pinned first. Custom categories ride the SAME customization keys,
                // so renames/hides/reorders apply to them with no extra code.
                val folders = cats.railCategories(
                    cust,
                    kids = profile?.isKids == true,
                    alphaRest = sort == SettingsRepository.SortMode.ALPHA,
                )
                val multiSourceNames = c.sourceNames.takeIf { it.size > 1 }.orEmpty()
                val categoriesById = cats.associateBy { it.id }
                // Favourites first, above the catalogs: under them, at the head of the playlist's own entries,
                // nobody found it (Jonathan, 7 Nov 2026). History and All stay with the playlist.
                val (favorites, ownRest) = DEFAULT_RAIL.partition { it.key == LiveKey.Favorites }
                favorites + catalogList.map { LiveRailItem(it.key, title = it.title) } + ownRest + folders.map { e ->
                    LiveRailItem(
                        key = e.categoryId?.let { LiveKey.Folder(it) } ?: LiveKey.Custom(e.customId!!),
                        title = e.displayName,
                        providerName = e.categoryId
                            ?.let(categoriesById::get)
                            ?.sourceId
                            ?.let(multiSourceNames::get),
                        sourceId = e.categoryId?.let(categoriesById::get)?.sourceId,
                    )
                }
            }
        }
        // Sorting and rebuilding every category folder runs off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), DEFAULT_RAIL)

    val selected = MutableStateFlow<LiveKey>(LiveKey.All)

    /**
     * Set while the view model is serving More -> Favourites or More -> History rather than the
     * browse section. Those screens are one folder each: the selection is theirs to fix, and it must
     * not be persisted as the remembered category.
     */
    private var lockedKey: LiveKey? = null

    /** What the browse section was showing before the pin, so [unlock] can put it back. */
    private var previousKey: LiveKey? = null

    val isLocked: Boolean get() = lockedKey != null

    fun lock(key: LiveKey) {
        if (lockedKey == null) previousKey = selected.value
        lockedKey = key
        selected.value = key
    }

    /**
     * Release the pin and put the browse section back where it was.
     *
     * **This is not optional bookkeeping.** On the television the view model is a single instance
     * shared between the browse section and the More screens, so a pin that outlived the screen that
     * took it froze the section's category rail — focusable, but every click a no-op. The screen that
     * locks therefore unlocks on dispose.
     */
    fun unlock() {
        val previous = previousKey ?: return
        lockedKey = null
        previousKey = null
        selected.value = previous
    }

    fun select(key: LiveKey) {
        if (lockedKey != null) return
        selected.value = key
        // Core's lazy-catalogue drain fills categories in the provider's order; tell it the user is
        // here so this one is served next. A no-op for every playlist that was not
        // lazily added, and for a category that is already complete.
        if (key is LiveKey.Folder) {
            scope.launch { runCatching { catalogPriority.requestFirst(key.id) } }
        } else {
            catalogPriority.clear()
        }
    }

    /**
     * Mayo TV opens Movies and Series on the first catalog ("Weiterschauen" when something is
     * started, else "Gerade angesagt"), once, while the user has not picked anything yet.
     */
    fun openOnFirstCatalog() {
        scope.launch {
            val first = kotlinx.coroutines.withTimeoutOrNull(OPEN_WAIT_MS) {
                railItems.first { list -> list.any { VodCatalogKeys.isCatalog(it.key) } }
            }?.firstOrNull { VodCatalogKeys.isCatalog(it.key) } ?: return@launch
            if (selected.value == LiveKey.All && lockedKey == null) selected.value = first.key
        }
    }

    companion object {
        private const val OPEN_WAIT_MS = 15_000L
        val DEFAULT_RAIL = listOf(
            LiveRailItem(LiveKey.Favorites, icon = OwnTVIcon.FAVORITE),
            LiveRailItem(LiveKey.History, icon = OwnTVIcon.HISTORY),
            LiveRailItem(LiveKey.All),
        )
    }
}

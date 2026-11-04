package tv.own.owntv.features.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The channel row on Home that stood for "Recently watched": a Live TV category of the user's choice
 * instead, Magenta TV until one is chosen (Jonathan, 4 Nov 2026).
 *
 * Hiding and moving a channel from Home applies to Home only: Live TV keeps its own order and shows
 * every channel. So both are kept here, in the app's preferences, and not in core's customisation.
 * Choosing [RECENT] gives the row back to core's recently watched channels.
 */
class HomeCatalog(
    context: Context,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val customize: CustomizationStore,
) {
    class Category(val key: String, val id: Long, val name: String)

    /**
     * The category's channels in Home's order ([all]); Home shows the first [SHOWN] of them ([channels]).
     * Move offers [all], so a channel further down can be brought into the row (Jonathan, 4 Nov 2026:
     * Romance TV sat at 87 of 110 and was not in the row at all).
     */
    class Row(val category: Category, val all: List<ChannelEntity>) {
        val channels: List<ChannelEntity> = all.take(SHOWN)
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)

    /** Counts every change, so Home can load the row again after one made elsewhere (Settings). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** The chosen category's key, [RECENT], or null while nothing was chosen (Magenta TV then). */
    fun chosen(): String? = prefs.getString(CHOSEN, null)

    fun choose(key: String) {
        prefs.edit().putString(CHOSEN, key).apply()
        _changes.value++
    }

    /** The profile's Live TV categories as Live TV names them, without those hidden there. */
    suspend fun categories(): List<Category> {
        val profile = activeProfileSources(settings, sourceDao).first { it.profileId >= 0 }
        val custom = customize.observe(profile.profileId, MediaType.LIVE).first()
        return categoryDao.observe(profile.sourceIdsFor(MediaType.LIVE), MediaType.LIVE).first().mapNotNull { cat ->
            val key = CustomizeKeys.category(cat)
            if (key in custom.hiddenItems) null else Category(key, cat.id, custom.itemNames[key] ?: cat.name)
        }
    }

    /** The row's category and its channels in Home's order, or null when the row shows recently watched. */
    suspend fun load(profileId: Long): Row? {
        val category = current(categories()) ?: return null
        val custom = customize.observe(profileId, MediaType.LIVE).first()
        val channels = channelDao.snapshotByCategoryManual(category.id, profileId, category.key, LIMIT)
            .filter { CustomizeKeys.channel(it) !in custom.hiddenItems }
        return Row(category, arrange(channels, { CustomizeKeys.channel(it) }, hidden(), order(category.key)))
    }

    /** Of [all], the category the row shows: the chosen one, else Magenta TV's; null for recently watched. */
    fun current(all: List<Category>): Category? = when (val chosen = chosen()) {
        RECENT -> null
        null -> all.firstOrNull { it.name.contains(DEFAULT_NAME, ignoreCase = true) }
        else -> all.firstOrNull { it.key == chosen }
    }

    fun hidden(): Set<String> = prefs.getStringSet(HIDDEN, null).orEmpty()

    fun hide(channel: ChannelEntity) {
        prefs.edit().putStringSet(HIDDEN, hidden() + CustomizeKeys.channel(channel)).apply()
    }

    /** Shows every channel hidden from Home again. */
    fun showAll() {
        prefs.edit().remove(HIDDEN).apply()
        _changes.value++
    }

    fun order(categoryKey: String): List<String> =
        prefs.getString(ORDER + categoryKey, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    fun saveOrder(categoryKey: String, channels: List<ChannelEntity>) {
        prefs.edit().putString(ORDER + categoryKey, channels.joinToString(SEPARATOR.toString()) { CustomizeKeys.channel(it) }).apply()
    }

    companion object {
        const val RECENT = "recent"
        private const val DEFAULT_NAME = "Magenta TV"
        private const val PREFS = "tvplayer"
        private const val CHOSEN = "home_catalog"
        private const val HIDDEN = "home_catalog_hidden"
        private const val ORDER = "home_catalog_order:"
        // Keys are "sourceId:remoteId"; a remote id never holds a line break.
        private const val SEPARATOR = '\n'
        /** Every channel of the category: Move must reach all of them. */
        private const val LIMIT = 1000
        /** How many of them the row shows. */
        const val SHOWN = 30

        /**
         * [channels] without the [hidden] ones, those Home has an [order] for first in that order, the
         * rest after them in Live TV's order: a channel the playlist adds later lands at the end.
         */
        fun <T> arrange(channels: List<T>, keyOf: (T) -> String, hidden: Set<String>, order: List<String>): List<T> {
            val shown = channels.filter { keyOf(it) !in hidden }
            val rank = order.withIndex().associate { (i, key) -> key to i }
            return shown.sortedBy { rank[keyOf(it)] ?: Int.MAX_VALUE }
        }
    }
}

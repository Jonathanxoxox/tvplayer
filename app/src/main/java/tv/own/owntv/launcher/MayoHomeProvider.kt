package tv.own.owntv.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Liefert Mayo Home (dem eigenen Launcher) die Live-Kategorien, ihre Sender und die zuletzt
 * gesehenen Sender, jeweils mit der laufenden Sendung und dem Link zum Abspielen.
 *
 * Herausgegeben werden nur Namen, Logos, Sendungstitel und der owntv://-Link, den auch die
 * Startbildschirm-Kacheln tragen; keine Stream-Adressen oder Zugangsdaten.
 *
 *   content://<applicationId>.home/categories          id, name
 *   content://<applicationId>.home/channels?category=ID id, name, logo, now_title, now_start, now_end, link
 *   content://<applicationId>.home/recent               wie channels
 */
class MayoHomeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 60
        return runBlocking {
            withTimeoutOrNull(8_000) {
                when (uri.lastPathSegment) {
                    "categories" -> categories()
                    "channels" -> uri.getQueryParameter("category")?.toLongOrNull()?.let { channels(it, limit) }
                    "recent" -> recent(limit)
                    else -> null
                }
            }
        }
    }

    private class Ctx(val profileId: Long, val sources: List<Long>)

    private suspend fun ctx(): Ctx? {
        val koin = GlobalContext.getOrNull() ?: return null
        val profile = activeProfileSources(koin.get<SettingsRepository>(), koin.get<SourceDao>())
            .first { it.profileId >= 0 }
        return Ctx(profile.profileId, profile.sourceIdsFor(MediaType.LIVE))
    }

    private suspend fun categories(): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "name", "source_id"))
        val c = ctx() ?: return cursor
        val koin = GlobalContext.get()
        val custom = koin.get<CustomizationStore>().observe(c.profileId, MediaType.LIVE).first()
        koin.get<CategoryDao>().observe(c.sources, MediaType.LIVE).first().forEach { cat ->
            val key = CustomizeKeys.category(cat)
            if (key in custom.hiddenItems) return@forEach
            cursor.addRow(arrayOf<Any?>(cat.id, custom.itemNames[key] ?: cat.name, cat.sourceId))
        }
        return cursor
    }

    private suspend fun channels(categoryId: Long, limit: Int): Cursor {
        val c = ctx() ?: return channelCursor()
        val koin = GlobalContext.get()
        val cat = koin.get<CategoryDao>().getById(categoryId) ?: return channelCursor()
        val list = koin.get<ChannelDao>().snapshotByCategoryManual(categoryId, c.profileId, CustomizeKeys.category(cat), limit)
        return fill(c, list)
    }

    private suspend fun recent(limit: Int): Cursor {
        val c = ctx() ?: return channelCursor()
        val list = GlobalContext.get().get<ChannelDao>().recentlyWatched(c.profileId, limit).first()
        return fill(c, list)
    }

    private fun channelCursor() = MatrixCursor(arrayOf("id", "source_id", "name", "logo", "now_title", "now_start", "now_end", "link"))

    private suspend fun fill(c: Ctx, list: List<ChannelEntity>): Cursor {
        val cursor = channelCursor()
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsRepository>()
        val custom = koin.get<CustomizationStore>().observe(c.profileId, MediaType.LIVE).first()
        val offset = settings.epgOffsetMinutes.first()
        val epg = koin.get<LiveEpgReader>()
        val now = System.currentTimeMillis()
        for (ch in list) {
            val key = CustomizeKeys.channel(ch)
            if (key in custom.hiddenItems) continue
            val current = runCatching { epg.nowNext(ch, custom, offset) }.getOrNull()?.now
                ?.takeIf { it.startMs <= now && it.stopMs > now }
            cursor.addRow(arrayOf<Any?>(
                ch.id, ch.sourceId, custom.itemNames[key] ?: ch.name, ch.displayLogoUrl,
                current?.title, current?.startMs, current?.stopMs, LauncherRows.playUri(ch).toString(),
            ))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

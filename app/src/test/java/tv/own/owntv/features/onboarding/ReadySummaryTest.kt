package tv.own.owntv.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The READY page's channels line: it only says "set up" when channels arrived or are still coming. */
class ReadySummaryTest {

    @Test
    fun `no playlist is not set up`() {
        val line = channelsLine(playlists = 0, channels = 0, importing = false)
        assertEquals(ChannelsLine.NO_PLAYLIST, line)
        assertFalse(line.done)
    }

    @Test
    fun `an import still running counts as loading`() {
        val line = channelsLine(playlists = 1, channels = 0, importing = true)
        assertEquals(ChannelsLine.LOADING, line)
        assertTrue(line.done)
    }

    @Test
    fun `a playlist that brought no channels is not shown as done`() {
        val line = channelsLine(playlists = 1, channels = 0, importing = false)
        assertEquals(ChannelsLine.NONE_LOADED, line)
        assertFalse(line.done)
    }

    @Test
    fun `channels that arrived are counted, during and after the import`() {
        assertEquals(ChannelsLine.COUNT, channelsLine(playlists = 2, channels = 240, importing = true))
        assertEquals(ChannelsLine.COUNT, channelsLine(playlists = 1, channels = 1, importing = false))
    }
}

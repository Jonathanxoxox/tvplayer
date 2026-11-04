package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** How Home's own hiding and order apply to a category's channels. */
class HomeCatalogTest {

    private fun arrange(channels: List<String>, hidden: Set<String> = emptySet(), order: List<String> = emptyList()) =
        HomeCatalog.arrange(channels, { it }, hidden, order)

    @Test
    fun `without anything kept the row is Live TV's order`() {
        assertEquals(listOf("a", "b", "c"), arrange(listOf("a", "b", "c")))
    }

    @Test
    fun `hidden channels are left out`() {
        assertEquals(listOf("a", "c"), arrange(listOf("a", "b", "c"), hidden = setOf("b")))
    }

    @Test
    fun `the kept order comes first, channels new to the category after it`() {
        assertEquals(listOf("c", "a", "b", "d"), arrange(listOf("a", "b", "c", "d"), order = listOf("c", "a", "b")))
    }

    @Test
    fun `a kept channel the playlist dropped is skipped`() {
        assertEquals(listOf("b", "a"), arrange(listOf("a", "b"), order = listOf("x", "b", "a")))
    }
}

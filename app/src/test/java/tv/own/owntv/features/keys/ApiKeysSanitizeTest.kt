package tv.own.owntv.features.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/** How typed, pasted or phone-sent keys are cleaned and shown. */
class ApiKeysSanitizeTest {

    @Test
    fun `whitespace, quotes and line breaks go`() {
        assertEquals("abc123", ApiKeys.sanitize("  \"abc 123\"\n"))
        assertEquals("sk_live-1.2", ApiKeys.sanitize("<sk_live-1.2>\t"))
    }

    @Test
    fun `only key characters remain`() {
        assertEquals("abcXYZ09_-.", ApiKeys.sanitize("abcXYZ09_-.äö€/+=:"))
        assertEquals("", ApiKeys.sanitize("   "))
    }

    @Test
    fun `length is capped`() {
        assertEquals(ApiKeys.MAX_LENGTH, ApiKeys.sanitize("a".repeat(ApiKeys.MAX_LENGTH + 50)).length)
    }

    @Test
    fun `masking shows only the end`() {
        assertEquals("", ApiKeys.mask(""))
        assertEquals("••••", ApiKeys.mask("abc"))
        assertEquals("••••c3f9", ApiKeys.mask("0123456789abcdefc3f9"))
    }
}

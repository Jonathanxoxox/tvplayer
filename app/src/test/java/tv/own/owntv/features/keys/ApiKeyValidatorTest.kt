package tv.own.owntv.features.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.search.JevClient

/** How each service's answer to a key check is read: a wrong key apart from an unreachable service. */
class ApiKeyValidatorTest {

    @Test
    fun `tmdb answers`() {
        assertEquals(KeyCheck.Valid, ApiKeyValidator.tmdbResult(200))
        assertEquals(KeyCheck.Invalid, ApiKeyValidator.tmdbResult(401))
        assertEquals(KeyCheck.Unreachable(503), ApiKeyValidator.tmdbResult(503))
        assertEquals(KeyCheck.Unreachable(429), ApiKeyValidator.tmdbResult(429))
    }

    @Test
    fun `elevenlabs tells a wrong key from a restricted one`() {
        assertEquals(KeyCheck.Valid, ApiKeyValidator.elevenLabsResult(200, "{}"))
        val wrong = """{"detail":{"type":"authentication_error","code":"unauthorized","message":"Invalid API key","status":"invalid_api_key"}}"""
        assertEquals(KeyCheck.Invalid, ApiKeyValidator.elevenLabsResult(401, wrong))
        val restricted = """{"detail":{"status":"missing_permissions","message":"The API key you used is missing the permission user_read"}}"""
        assertEquals(KeyCheck.Valid, ApiKeyValidator.elevenLabsResult(401, restricted))
        assertEquals(KeyCheck.Invalid, ApiKeyValidator.elevenLabsResult(401, "not json"))
        assertEquals(KeyCheck.Unreachable(500), ApiKeyValidator.elevenLabsResult(500, ""))
    }

    @Test
    fun `typesafe answers`() {
        assertEquals(KeyCheck.Valid, ApiKeyValidator.typeSafeResult(200))
        assertEquals(KeyCheck.Invalid, ApiKeyValidator.typeSafeResult(401))
        assertEquals(KeyCheck.Invalid, ApiKeyValidator.typeSafeResult(403))
        assertEquals(KeyCheck.Unreachable(502), ApiKeyValidator.typeSafeResult(502))
    }

    @Test
    fun `tmdb read access token is recognised`() {
        val token = "eyJhbGciOiJIUzI1NiJ9." + "a".repeat(120) + ".signature_part-1"
        assertTrue(ApiKeyValidator.looksLikeTmdbReadToken(token))
        assertTrue(ApiKeyValidator.looksLikeTmdbReadToken("  \"$token\"\n"))
        assertFalse(ApiKeyValidator.looksLikeTmdbReadToken("0123456789abcdef0123456789abcdef"))
        assertFalse(ApiKeyValidator.looksLikeTmdbReadToken(""))
    }

    @Test
    fun `a key entered later is used without a restart`() {
        var key = ""
        val jev = JevClient(okhttp3.OkHttpClient()) { key }
        assertFalse(jev.available)
        key = "ts_live_123"
        assertTrue(jev.available)
        key = ""
        assertFalse(jev.available)
    }
}

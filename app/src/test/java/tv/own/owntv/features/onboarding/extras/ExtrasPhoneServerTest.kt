package tv.own.owntv.features.onboarding.extras

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.keys.ApiService

/** What the Extras phone page accepts: who may ask, and how a submitted form becomes keys. */
class ExtrasPhoneServerTest {

    @Test
    fun `form fields become sanitised keys per service`() {
        val keys = ExtrasPhoneServer.parseKeys("tmdb=%20abc123%0A&elevenlabs=sk_live-1.2&typesafe=")
        assertEquals(mapOf(ApiService.TMDB to "abc123", ApiService.ELEVENLABS to "sk_live-1.2"), keys)
    }

    @Test
    fun `empty, unknown and broken fields are ignored`() {
        assertEquals(emptyMap<ApiService, String>(), ExtrasPhoneServer.parseKeys(""))
        assertEquals(emptyMap<ApiService, String>(), ExtrasPhoneServer.parseKeys("other=abc&tmdb=%20%20"))
        assertEquals(mapOf(ApiService.TYPESAFE to "ok1"), ExtrasPhoneServer.parseKeys("tmdb=%zz&typesafe=ok1"))
    }

    @Test
    fun `only home network addresses are answered`() {
        listOf("192.168.178.20", "10.0.0.7", "172.20.1.1", "127.0.0.1", "169.254.3.4", "fd12:3456::1", "fe80::1")
            .forEach { assertTrue(it, ExtrasPhoneServer.isLocal(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "172.32.0.1", "2001:4860:4860::8888")
            .forEach { assertFalse(it, ExtrasPhoneServer.isLocal(InetAddress.getByName(it))) }
        assertFalse(ExtrasPhoneServer.isLocal(null))
    }
}

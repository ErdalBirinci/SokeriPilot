package app.glcopilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutSecurityTest {

    @Test
    fun `sha1 matches the Nightscout protocol digest for a known value`() {
        assertEquals(
            "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
            NightscoutSecurity.sha1Hex("test")
        )
    }

    @Test
    fun `https url is accepted and trailing slash removed`() {
        val result = NightscoutSecurity.validateBaseUrl("https://ns.example.com/")
        assertTrue(result is UrlValidation.Valid)
        assertEquals("https://ns.example.com", (result as UrlValidation.Valid).normalizedBaseUrl)
    }

    @Test
    fun `reverse proxy sub path is preserved`() {
        val result = NightscoutSecurity.validateBaseUrl("https://example.com/nightscout/")
        assertEquals(
            "https://example.com/nightscout",
            (result as UrlValidation.Valid).normalizedBaseUrl
        )
    }

    @Test
    fun `cleartext http is rejected`() {
        assertTrue(NightscoutSecurity.validateBaseUrl("http://ns.example.com") is UrlValidation.Invalid)
    }

    @Test
    fun `non https schemes are rejected`() {
        assertTrue(NightscoutSecurity.validateBaseUrl("ftp://ns.example.com") is UrlValidation.Invalid)
        assertTrue(NightscoutSecurity.validateBaseUrl("javascript:alert(1)") is UrlValidation.Invalid)
    }

    @Test
    fun `credentials in url are rejected`() {
        assertTrue(
            NightscoutSecurity.validateBaseUrl("https://user:pass@ns.example.com") is UrlValidation.Invalid
        )
    }

    @Test
    fun `query and fragment are rejected`() {
        assertTrue(NightscoutSecurity.validateBaseUrl("https://ns.example.com/?a=1") is UrlValidation.Invalid)
        assertTrue(NightscoutSecurity.validateBaseUrl("https://ns.example.com/#x") is UrlValidation.Invalid)
    }

    @Test
    fun `blank and whitespace urls are rejected`() {
        assertTrue(NightscoutSecurity.validateBaseUrl("   ") is UrlValidation.Invalid)
        assertTrue(NightscoutSecurity.validateBaseUrl("https://ns .example.com") is UrlValidation.Invalid)
    }

    @Test
    fun `path traversal is rejected`() {
        assertTrue(
            NightscoutSecurity.validateBaseUrl("https://ns.example.com/a/../b") is UrlValidation.Invalid
        )
    }

    @Test
    fun `digest format is validated`() {
        assertTrue(NightscoutSecurity.isValidDigest("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"))
        assertFalse(NightscoutSecurity.isValidDigest("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))
        assertFalse(NightscoutSecurity.isValidDigest("a94a8fe5"))
        assertFalse(NightscoutSecurity.isValidDigest("z".repeat(40)))
    }
}

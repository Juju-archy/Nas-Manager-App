package com.nasmanagerapp.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyAuthTest {

    @Test
    fun `none adds no header`() {
        assertEquals(emptyList<Pair<String, String>>(), ProxyAuth.None.headers())
        assertNull(handshakeRequest("wss://nas.example.com/api/current", ProxyAuth.None).header("Authorization"))
    }

    @Test
    fun `basic auth is a single Authorization header`() {
        // base64("alice:s3cret")
        assertEquals(
            listOf("Authorization" to "Basic YWxpY2U6czNjcmV0"),
            ProxyAuth.Basic("alice", "s3cret").headers(),
        )
    }

    @Test
    fun `basic auth encodes non-Latin-1 characters as UTF-8`() {
        // base64("user:пароль") with the password in UTF-8.
        assertEquals(
            "Basic dXNlcjrQv9Cw0YDQvtC70Yw=",
            ProxyAuth.Basic("user", "пароль").headers().single().second,
        )
    }

    @Test
    fun `cloudflare access sends the standard header pair, trimmed`() {
        assertEquals(
            listOf(
                "CF-Access-Client-Id" to "0123abcd.access",
                "CF-Access-Client-Secret" to "secret-value",
            ),
            ProxyAuth.CloudflareAccess(" 0123abcd.access ", "secret-value\n").headers(),
        )
    }

    @Test
    fun `handshake request carries the headers`() {
        val request = handshakeRequest(
            "wss://nas.example.com/api/current",
            ProxyAuth.CloudflareAccess("id.access", "secret"),
        )
        assertEquals("id.access", request.header("CF-Access-Client-Id"))
        assertEquals("secret", request.header("CF-Access-Client-Secret"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `validation`() {
        assertNull(ProxyAuth.None.proxyAuthError())
        assertNull(ProxyAuth.Basic("alice", "s3cret").proxyAuthError())
        assertNull(ProxyAuth.CloudflareAccess("id.access", "secret").proxyAuthError())

        assertNotNull(ProxyAuth.Basic("", "s3cret").proxyAuthError())
        assertNotNull(ProxyAuth.Basic("alice", "").proxyAuthError())
        assertNotNull(ProxyAuth.Basic("al:ice", "s3cret").proxyAuthError())
        assertNotNull(ProxyAuth.Basic("alice", "s3\ncret").proxyAuthError())
        assertNotNull(ProxyAuth.CloudflareAccess(" ", "secret").proxyAuthError())
        assertNotNull(ProxyAuth.CloudflareAccess("id.access", "sec ret").proxyAuthError())
        assertNotNull(ProxyAuth.CloudflareAccess("id.access", "sécret").proxyAuthError())
    }

    @Test
    fun `every valid input builds a request without throwing`() {
        // Request.Builder.header throws IllegalArgumentException on invalid characters: whatever
        // passes validation must never reach it.
        listOf(
            ProxyAuth.Basic("ünïcödé", "pässwörd with spaces"),
            ProxyAuth.CloudflareAccess("\tid.access ", " secret!~"),
        ).forEach {
            assertNull(it.proxyAuthError())
            handshakeRequest("wss://nas.example.com/api/current", it)
        }
    }

    @Test
    fun `secrets never show in toString`() {
        assertFalse(ProxyAuth.Basic("alice", "s3cret").toString().contains("s3cret"))
        assertFalse(ProxyAuth.CloudflareAccess("id", "top-secret").toString().contains("top-secret"))
    }

    @Test
    fun `mode maps to the right ProxyAuth`() {
        assertEquals(ProxyAuth.None, ProxyAuthMode.NONE.toProxyAuth("ignored", "ignored"))
        assertEquals(ProxyAuth.Basic("u", "p"), ProxyAuthMode.BASIC.toProxyAuth("u", "p"))
        assertEquals(ProxyAuth.CloudflareAccess("i", "s"), ProxyAuthMode.CLOUDFLARE_ACCESS.toProxyAuth("i", "s"))
    }

    @Test
    fun `rejection message depends on the code and whether credentials were set`() {
        assertTrue(proxyRejectionMessage(401, proxyAuthConfigured = true).contains("refused its credentials"))
        assertTrue(proxyRejectionMessage(403, proxyAuthConfigured = false).contains("set its credentials"))
        assertTrue(proxyRejectionMessage(200, proxyAuthConfigured = false).contains("HTTP 200"))
    }
}

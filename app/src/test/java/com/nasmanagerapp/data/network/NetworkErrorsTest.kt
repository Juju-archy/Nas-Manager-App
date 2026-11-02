package com.nasmanagerapp.data.network

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkErrorsTest {

    @Test
    fun `timeout is unreachable`() {
        // How Android 17 reports a LAN connection blocked by the local network permission.
        assertTrue(isServerUnreachable(SocketTimeoutException("timeout")))
    }

    @Test
    fun `refused connection and unknown host are unreachable`() {
        assertTrue(isServerUnreachable(ConnectException("refused")))
        assertTrue(isServerUnreachable(UnknownHostException("nas.local")))
        assertTrue(isServerUnreachable(IOException("generic")))
    }

    @Test
    fun `tls failure is not unreachable`() {
        assertFalse(isServerUnreachable(SSLHandshakeException("untrusted certificate")))
    }

    @Test
    fun `non io error is not unreachable`() {
        assertFalse(isServerUnreachable(IllegalStateException("parse error")))
    }

    // IP literals: InetAddress parses them without any DNS lookup.

    @Test
    fun `private ipv4 ranges are local`() {
        assertTrue(isLocalNetworkHost("192.168.1.10"))
        assertTrue(isLocalNetworkHost("10.0.0.5"))
        assertTrue(isLocalNetworkHost("172.16.0.1"))
        assertTrue(isLocalNetworkHost("172.31.255.254"))
        assertTrue(isLocalNetworkHost("169.254.10.1"))
    }

    @Test
    fun `public, cgnat and loopback ipv4 are not local`() {
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        assertFalse(isLocalNetworkHost("172.32.0.1"))
        assertFalse(isLocalNetworkHost("100.64.0.1")) // Tailscale
        assertFalse(isLocalNetworkHost("127.0.0.1"))
    }

    @Test
    fun `ipv6 unique local and link local are local, global is not`() {
        assertTrue(isLocalNetworkHost("fd12:3456::1"))
        assertTrue(isLocalNetworkHost("fc00::1"))
        assertTrue(isLocalNetworkHost("fe80::1"))
        assertFalse(isLocalNetworkHost("2001:4860:4860::8888"))
        assertFalse(isLocalNetworkHost("::1"))
    }

    @Test
    fun `mdns names are local without resolving`() {
        val failIfCalled: (String) -> List<InetAddress> = { error("must not resolve $it") }
        assertTrue(isLocalNetworkHost("truenas.local", failIfCalled))
        assertTrue(isLocalNetworkHost("TrueNAS.Local.", failIfCalled))
    }

    @Test
    fun `hostname is local only if it resolves to a local address`() {
        val lan = InetAddress.getByName("192.168.1.10")
        val public = InetAddress.getByName("203.0.113.7")
        assertTrue(isLocalNetworkHost("nas.home.example", { listOf(lan) }))
        assertTrue(isLocalNetworkHost("nas.home.example", { listOf(public, lan) }))
        assertFalse(isLocalNetworkHost("nas.example.com", { listOf(public) }))
    }

    @Test
    fun `unresolvable hostname is not local`() {
        assertFalse(isLocalNetworkHost("nas.example.com", { throw UnknownHostException(it) }))
    }
}

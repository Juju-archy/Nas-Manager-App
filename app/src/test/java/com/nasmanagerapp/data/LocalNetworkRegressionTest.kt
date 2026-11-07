package com.nasmanagerapp.data

import com.google.gson.JsonParser
import com.nasmanagerapp.data.auth.LocalServerUnreachableException
import com.nasmanagerapp.data.auth.TrueNasAuthRepository
import com.nasmanagerapp.data.dashboard.DashboardApiException
import com.nasmanagerapp.data.dashboard.DashboardLocalServerUnreachableException
import com.nasmanagerapp.data.dashboard.DashboardRepository
import com.nasmanagerapp.data.dashboard.networkCauseOf
import com.nasmanagerapp.data.network.CredentialsStore
import com.nasmanagerapp.data.network.TrueNasRpcClient
import com.nasmanagerapp.data.network.isServerUnreachable
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the Android 17 local network behavior of release 1.2.2 across the REST → JSON-RPC
 * migration (issue #6): an unreachable server on the local network must still surface as
 * [LocalServerUnreachableException] at login and [DashboardLocalServerUnreachableException] on the
 * dashboard, which is what shows the "Allow access" notice (`LocalNetworkAccess.kt`).
 *
 * The real "is it on the local network" check resolves hostnames, so it's replaced by a fixed
 * answer here; "unreachable" is a closed port on the loopback interface (connection refused).
 */
class LocalNetworkRegressionTest {

    /** `127.0.0.1:<port>` with nothing listening on it. */
    private fun closedAddress(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "127.0.0.1:$port"
    }

    private fun signedIn() = CredentialsStore().apply { username = "admin"; password = "hunter2" }

    private fun dashboardRepository(address: String, isLocalHost: Boolean): DashboardRepository {
        val rpc = TrueNasRpcClient(OkHttpClient(), signedIn(), uriProvider = { "ws://$address/api/current" })
        return DashboardRepository(rpc, baseUrlProvider = { "http://$address" }, isLocalHost = { isLocalHost })
    }

    @Test
    fun `login to an unreachable local server is flagged`() {
        val repository = TrueNasAuthRepository(
            TrueNasRpcClient(OkHttpClient(), CredentialsStore(), uriProvider = { null }),
            CredentialsStore(),
            isLocalHost = { true },
        )

        val result = runBlocking { repository.login("http://${closedAddress()}", "admin", "hunter2", acceptHttpRisks = true) }

        assertTrue(result.exceptionOrNull() is LocalServerUnreachableException)
    }

    @Test
    fun `login to an unreachable non-local server is not flagged`() {
        val repository = TrueNasAuthRepository(
            TrueNasRpcClient(OkHttpClient(), CredentialsStore(), uriProvider = { null }),
            CredentialsStore(),
            isLocalHost = { false },
        )

        val result = runBlocking { repository.login("http://${closedAddress()}", "admin", "hunter2", acceptHttpRisks = true) }

        val error = result.exceptionOrNull()
        assertTrue(error != null && error !is LocalServerUnreachableException)
    }

    @Test
    fun `system info from an unreachable local server is flagged`() {
        val result = runBlocking { dashboardRepository(closedAddress(), isLocalHost = true).getSystemInfo() }

        assertTrue(result.exceptionOrNull() is DashboardLocalServerUnreachableException)
    }

    @Test
    fun `system info from an unreachable non-local server is not flagged`() {
        val result = runBlocking { dashboardRepository(closedAddress(), isLocalHost = false).getSystemInfo() }

        val error = result.exceptionOrNull()
        assertTrue(error is DashboardApiException && error !is DashboardLocalServerUnreachableException)
    }

    @Test
    fun `system info is still flagged after another call already failed to connect`() {
        // The 2s polling usually hits the connection failure first and puts the client in its
        // reconnect backoff; system info must not lose the flag because of that.
        val repository = dashboardRepository(closedAddress(), isLocalHost = true)

        runBlocking { repository.getPools() }
        val result = runBlocking { repository.getSystemInfo() }

        assertTrue(result.exceptionOrNull() is DashboardLocalServerUnreachableException)
    }

    @Test
    fun `an error during the reconnect backoff keeps the network cause`() {
        val rpc = TrueNasRpcClient(OkHttpClient(), signedIn(), uriProvider = { "ws://${closedAddress()}/api/current" })

        val first = runCatching { runBlocking { rpc.call("pool.query", emptyList(), { it }) } }.exceptionOrNull()
        val second = runCatching { runBlocking { rpc.call("pool.query", emptyList(), { it }) } }.exceptionOrNull()

        for (error in listOf(first, second)) {
            assertTrue(error is DashboardApiException)
            assertTrue(isServerUnreachable(networkCauseOf(error!!)))
        }
    }

    @Test
    fun `system info reconnects right away instead of waiting out the backoff`() {
        // Once the permission is granted, DashboardScreen retries system info immediately: it must
        // actually reconnect rather than fail again from the backoff.
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JsonParser.parseString(text).asJsonObject
                val result = when (frame.get("method").asString) {
                    "auth.login_ex" -> """{"response_type":"SUCCESS"}"""
                    "system.info" -> SYSTEM_INFO
                    else -> "{}"
                }
                webSocket.send("""{"jsonrpc":"2.0","id":${frame.get("id")},"result":$result}""")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
        }))
        server.start()
        try {
            var address = closedAddress()
            val rpc = TrueNasRpcClient(OkHttpClient(), signedIn(), uriProvider = { "ws://$address/api/current" })
            val repository = DashboardRepository(rpc, baseUrlProvider = { "http://$address" }, isLocalHost = { true })

            assertFalse(runBlocking { repository.getPools() }.isSuccess)
            address = "${server.hostName}:${server.port}"
            val result = runBlocking { repository.getSystemInfo() }

            assertEquals("truenas", result.getOrThrow().hostname)
        } finally {
            try {
                server.shutdown()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val SYSTEM_INFO = """{"version":"25.10","hostname":"truenas","physmem":1,"model":null,"cores":1,""" +
            """"physical_cores":1,"loadavg":[0,0,0],"uptime_seconds":1,"system_manufacturer":null}"""
    }
}

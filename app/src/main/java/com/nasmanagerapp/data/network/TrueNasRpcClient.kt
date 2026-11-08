package com.nasmanagerapp.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nasmanagerapp.data.dashboard.DashboardApiException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "NasManager"

/** `auth.login_ex` refused the credentials; [message] is already user-facing. */
class TrueNasAuthFailedException(message: String) : Exception(message)

/** A JSON-RPC `error` frame. */
internal class RpcErrorException(val code: Int, message: String) : Exception(message)

/**
 * JSON-RPC 2.0 client for TrueNAS's `/api/current` WebSocket: one persistent socket per session,
 * opened lazily (and re-opened after a drop, with exponential backoff) and authenticated with
 * `auth.login_ex` before any data call. Frames are matched to callers by id; notifications (no
 * `id`) are ignored, except `core.get_jobs` `collection_update`s, which resolve job-yielding calls.
 */
class TrueNasRpcClient(
    private val okHttpClient: OkHttpClient,
    private val credentialsStore: CredentialsStore,
    private val uriProvider: () -> String?,
    /** How long opening the socket may take; only shortened by tests. */
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
) {
    private val gson = Gson()
    private val connectLock = Mutex()

    @Volatile
    private var session: Session? = null

    private var consecutiveFailures = 0

    @Volatile
    private var retryNotBeforeMillis = 0L

    /**
     * Why the last connect attempt failed (an [IOException] or a [ProxyRejectedException]), kept
     * for the errors raised during its backoff.
     */
    private var lastConnectFailure: Exception? = null

    private val _authRejected = MutableStateFlow(false)

    /**
     * True once a re-authentication with the stored credentials was refused (the credentials are
     * then cleared); reset by the next successful [login]. Lets the UI fall back to the sign-in
     * screen instead of retrying silently.
     */
    val authRejected: StateFlow<Boolean> = _authRejected.asStateFlow()

    /**
     * Opens a fresh session on [uri] authenticated as [username]/[secret] (password, or an API key
     * pasted in its place), going through the reverse proxy with [proxyAuth]. Replaces any existing
     * session. Network errors propagate as [IOException]s (e.g. `SSLHandshakeException`); a
     * refusal is a [TrueNasAuthFailedException], or a [ProxyRejectedException] from the proxy.
     */
    suspend fun login(uri: String, username: String, secret: String, proxyAuth: ProxyAuth = ProxyAuth.None) {
        connectLock.withLock {
            session?.close()
            session = null
            consecutiveFailures = 0
            retryNotBeforeMillis = 0L
            lastConnectFailure = null
            session = openAuthenticated(uri, username, secret, proxyAuth)
            _authRejected.value = false
        }
    }

    /**
     * Drops the session; the next [call] reconnects (and fails if the credentials were cleared).
     * Takes [connectLock] so it can't interleave with a [login]/reconnect in flight.
     */
    suspend fun close() {
        connectLock.withLock {
            session?.close()
            session = null
        }
    }

    /**
     * Calls [method] with positional [params] and parses its `result`. [isJob] marks a
     * job-yielding method (`app.upgrade`/`start`/`stop`): the result is the job id, taken from
     * whichever of the direct reply or the `core.get_jobs` notification arrives first.
     */
    suspend fun <T> call(
        method: String,
        params: List<Any?>,
        parse: (JsonElement) -> T,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        isJob: Boolean = false,
    ): T {
        // Network failures keep their IOException as the cause: DashboardRepository needs it to
        // tell an unreachable local server apart (Android 17 local network permission, see
        // `LocalNetworkAccess.kt`).
        var connected = false
        val result = try {
            withTimeout(timeoutMillis) {
                val session = obtainSession()
                connected = true
                session.request(method, params, isJob)
            }
        } catch (e: TimeoutCancellationException) {
            if (!connected) {
                // The call's deadline ran out before the socket was even open: to the user (and to
                // the local network check) that's an unreachable server, like a connect timeout.
                throw DashboardApiException("Couldn't reach the server.", SocketTimeoutException("Connection timed out."))
            }
            throw DashboardApiException(
                if (isJob) "The server didn't acknowledge the task in time." else "The server didn't respond in time."
            )
        } catch (e: RpcErrorException) {
            throw DashboardApiException(e.message ?: "Unknown error.")
        } catch (e: TrueNasAuthFailedException) {
            throw DashboardApiException(e.message ?: "Authentication failed.")
        } catch (e: ProxyRejectedException) {
            // No cause on purpose: the server was reached, so no local network notice.
            throw DashboardApiException(e.message ?: "Access refused by the reverse proxy.")
        } catch (e: IOException) {
            Log.w(TAG, "call '$method' failed: ${e.javaClass.name}: ${e.message}")
            throw DashboardApiException("Couldn't reach the server.", e)
        }
        return parse(result)
    }

    private suspend fun obtainSession(): Session = connectLock.withLock {
        session?.takeIf { it.isOpen }?.let { return@withLock it }
        session = null
        val username = credentialsStore.username
        val secret = credentialsStore.password
        val uri = uriProvider()
        if (username == null || secret == null || uri == null) {
            throw DashboardApiException("Not signed in.")
        }
        val now = System.currentTimeMillis()
        if (now < retryNotBeforeMillis) {
            when (val failure = lastConnectFailure) {
                is ProxyRejectedException -> throw DashboardApiException(failure.message ?: "Access refused by the reverse proxy.")
                else -> throw DashboardApiException("Couldn't reach the server.", failure)
            }
        }
        try {
            openAuthenticated(uri, username, secret, credentialsStore.proxyAuth).also {
                session = it
                consecutiveFailures = 0
                lastConnectFailure = null
            }
        } catch (e: TrueNasAuthFailedException) {
            // Stored credentials no longer work: stop retrying them from the periodic poll.
            scheduleRetryBackoff()
            credentialsStore.username = null
            credentialsStore.password = null
            credentialsStore.proxyAuth = ProxyAuth.None
            _authRejected.value = true
            throw e
        } catch (e: ProxyRejectedException) {
            // Not a sign-out: the proxy may only be refusing for a while (or showing a login page
            // after a config change); retried with backoff like a network failure.
            lastConnectFailure = e
            scheduleRetryBackoff()
            throw e
        } catch (e: IOException) {
            lastConnectFailure = e
            scheduleRetryBackoff()
            throw e
        }
    }

    /**
     * Lets the next [call] reconnect right away instead of waiting out the backoff — for explicit
     * retries (e.g. right after the local network permission was granted), never for the polling.
     */
    fun allowImmediateRetry() {
        retryNotBeforeMillis = 0L
    }

    private fun scheduleRetryBackoff() {
        consecutiveFailures++
        val backoff = (BACKOFF_BASE_MILLIS shl (consecutiveFailures - 1).coerceAtMost(10)).coerceAtMost(BACKOFF_MAX_MILLIS)
        retryNotBeforeMillis = System.currentTimeMillis() + backoff
    }

    private suspend fun openAuthenticated(uri: String, username: String, secret: String, proxyAuth: ProxyAuth): Session {
        // Like an API key, reverse proxy credentials must never travel in clear text.
        if (proxyAuth != ProxyAuth.None && isCleartext(uri)) {
            throw TrueNasAuthFailedException(
                "Reverse proxy credentials can't be used over an unencrypted (http://) connection. Use HTTPS."
            )
        }
        proxyAuth.proxyAuthError()?.let { throw TrueNasAuthFailedException(it) }
        val opened = CompletableDeferred<Unit>()
        val newSession = Session(gson, opened)
        val socket = okHttpClient.newWebSocket(handshakeRequest(uri, proxyAuth), newSession)
        newSession.socket = socket
        try {
            // withTimeoutOrNull: only this step's own deadline yields null (a connect failure,
            // IOException); a caller's enclosing call() timeout still propagates as cancellation.
            try {
                withTimeoutOrNull(connectTimeoutMillis) { opened.await() }
                    ?: throw IOException("Connection timed out.")
            } catch (e: HandshakeRejectedException) {
                throw ProxyRejectedException(e.httpCode, proxyRejectionMessage(e.httpCode, proxyAuth != ProxyAuth.None))
            }
            withTimeoutOrNull(DEFAULT_TIMEOUT_MILLIS) {
                authenticate(newSession, uri, username, secret)
                try {
                    newSession.request("core.set_options", listOf(mapOf("legacy_jobs" to false)), false)
                } catch (e: RpcErrorException) {
                    if (e.code != METHOD_NOT_FOUND) throw e
                }
            } ?: throw IOException("The server didn't respond in time.")
        } catch (e: RpcErrorException) {
            newSession.close()
            throw DashboardApiException(e.message ?: "Unknown error.")
        } catch (e: Throwable) {
            newSession.close()
            throw e
        }
        return newSession
    }

    private suspend fun authenticate(session: Session, uri: String, username: String, secret: String) {
        if (!API_KEY_PATTERN.matches(secret)) {
            tryLogin(session, passwordPayload(username, secret))?.let { throw TrueNasAuthFailedException(it) }
            return
        }
        // An API key must never travel in clear text, nor be sent first as a password.
        if (isCleartext(uri)) {
            throw TrueNasAuthFailedException("API keys can't be used over an unencrypted (ws://) connection. Use HTTPS.")
        }
        val keyFailure = tryLogin(
            session,
            mapOf("mechanism" to "API_KEY_PLAIN", "username" to username, "api_key" to secret),
        ) ?: return
        // Fall back for a password that merely looks like a key.
        val passwordFailure = tryLogin(session, passwordPayload(username, secret)) ?: return
        throw TrueNasAuthFailedException(if (passwordFailure == INVALID_CREDENTIALS) keyFailure else passwordFailure)
    }

    private fun passwordPayload(username: String, secret: String) =
        mapOf("mechanism" to "PASSWORD_PLAIN", "username" to username, "password" to secret)

    private fun isCleartext(uri: String): Boolean {
        val scheme = runCatching { java.net.URI(uri).scheme }.getOrNull()?.lowercase()
        return scheme != "wss" && scheme != "https"
    }

    /** Runs one `auth.login_ex`; null on success, else the user-facing failure message. */
    private suspend fun tryLogin(session: Session, payload: Map<String, String>): String? {
        val result = session.request("auth.login_ex", listOf(payload), false)
        val type = (result as? JsonObject)?.get("response_type")?.takeIf { it.isJsonPrimitive }?.asString
        return when (type) {
            "SUCCESS" -> null
            "OTP_REQUIRED" -> "This account requires two-factor authentication."
            "DENIED" -> "This account doesn't have API access."
            "EXPIRED" -> "Account credentials have expired."
            "REDIRECT" -> "Authentication must be performed on the active storage controller."
            else -> INVALID_CREDENTIALS
        }
    }

    /** One WebSocket connection and its in-flight calls. Listener callbacks run on OkHttp threads. */
    private class Session(
        private val gson: Gson,
        private val opened: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        lateinit var socket: WebSocket
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
        private val jobCalls = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        var isOpen = true
            private set

        suspend fun request(method: String, params: List<Any?>, isJob: Boolean): JsonElement {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<JsonElement>()
            pending[id] = deferred
            if (isJob) jobCalls.add(id)
            try {
                if (!isOpen) throw IOException("Connection closed.")
                val frame = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", id)
                    addProperty("method", method)
                    add("params", gson.toJsonTree(params))
                }
                if (!socket.send(gson.toJson(frame))) throw IOException("Connection closed.")
                return deferred.await()
            } finally {
                pending.remove(id)
                jobCalls.remove(id)
            }
        }

        fun close() {
            fail(IOException("Connection closed."))
            socket.close(NORMAL_CLOSURE, null)
        }

        private fun fail(cause: Throwable) {
            isOpen = false
            opened.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(cause) }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { gson.fromJson(text, JsonElement::class.java) }.getOrNull() as? JsonObject
                ?: return
            val idElement = frame.get("id")
            if (idElement == null || idElement.isJsonNull) {
                // `"id": null` + `error` is how a parse/invalid-request error is reported when the
                // server couldn't tell which call it belongs to: fail everything in flight.
                if (frame.get("error") is JsonObject) {
                    fail(RpcErrorException(errorCode(frame), errorMessage(frame)))
                } else {
                    handleNotification(frame)
                }
                return
            }
            val deferred = pending[idElement.asString] ?: return
            val error = frame.get("error")
            if (error is JsonObject) {
                deferred.completeExceptionally(RpcErrorException(errorCode(frame), errorMessage(frame)))
            } else {
                deferred.complete(frame.get("result") ?: JsonNull.INSTANCE)
            }
        }

        private fun errorCode(frame: JsonObject): Int =
            frame.getAsJsonObject("error")?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        private fun errorMessage(frame: JsonObject): String {
            val error = frame.getAsJsonObject("error")
            val reason = error?.getAsJsonObject("data")?.get("reason")
                ?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
            return reason
                ?: error?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "Unknown error."
        }

        /** Resolves job-yielding calls from `core.get_jobs` `added`/`changed` events; ignores the rest. */
        private fun handleNotification(frame: JsonObject) {
            if (frame.get("method")?.takeIf { it.isJsonPrimitive }?.asString != "collection_update") return
            val params = frame.get("params") as? JsonObject ?: return
            if (params.get("collection")?.takeIf { it.isJsonPrimitive }?.asString != "core.get_jobs") return
            val fields = params.get("fields") as? JsonObject ?: return
            val jobId = fields.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return
            val messageIds = fields.get("message_ids") as? JsonArray ?: return
            for (messageId in messageIds) {
                if (!messageId.isJsonPrimitive) continue
                val callId = messageId.asString
                if (callId in jobCalls) pending[callId]?.complete(JsonPrimitive(jobId.asLong))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing code=$code reason=$reason")
            fail(IOException("Connection closed."))
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(IOException("Connection closed."))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "WS onFailure: ${t.javaClass.name}: ${t.message} response=${response?.code ?: "-"}")
            // An HTTP response before onOpen means the handshake itself was answered with
            // something other than "101 Switching Protocols" (proxy refusal, login page...).
            if (response != null && !opened.isCompleted && response.code != 101) {
                fail(HandshakeRejectedException(response.code))
            } else {
                fail(t)
            }
        }
    }

    /** The handshake got a plain HTTP response ([httpCode]); turned into a [ProxyRejectedException]. */
    private class HandshakeRejectedException(val httpCode: Int) : Exception("HTTP $httpCode")

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_TIMEOUT_MILLIS = NetworkModule.TIMEOUT_SECONDS * 1_000L
        const val INVALID_CREDENTIALS = "Invalid username or password."
        const val BACKOFF_BASE_MILLIS = 1_000L
        const val BACKOFF_MAX_MILLIS = 30_000L
        const val METHOD_NOT_FOUND = -32601
        const val NORMAL_CLOSURE = 1000
        val API_KEY_PATTERN = Regex("^[0-9]+-[A-Za-z0-9_-]{16,}$")
    }
}

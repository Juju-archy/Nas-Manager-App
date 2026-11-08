package com.nasmanagerapp.data.network

import okhttp3.Credentials
import okhttp3.Request

/**
 * Credentials for an authenticating reverse proxy in front of TrueNAS (GitHub issue #8). They are
 * sent as HTTP headers on the WebSocket handshake only — the proxy checks them once, then lets the
 * upgraded connection through, where TrueNAS authenticates with `auth.login_ex` as usual. Never
 * sent over cleartext (`ws://`), never to anything but the NAS socket (not `imageOkHttpClient`).
 * See `CONNECTIVITY_TODO.md`.
 */
sealed interface ProxyAuth {
    /** No reverse proxy, or one that doesn't authenticate: no extra header. */
    data object None : ProxyAuth

    /**
     * HTTP Basic auth at the proxy: Pangolin's "header auth", nginx/Traefik/Caddy/HAProxy basic
     * auth, Authelia/Authentik basic auth...
     */
    data class Basic(val user: String, val password: String) : ProxyAuth {
        override fun toString(): String = "Basic(user='$user', password='***')"
    }

    /** A Cloudflare Access service token, sent as its standard header pair. */
    data class CloudflareAccess(val clientId: String, val clientSecret: String) : ProxyAuth {
        override fun toString(): String = "CloudflareAccess(clientId='$clientId', clientSecret='***')"
    }
}

/**
 * Which [ProxyAuth] the login form edits, with its two fields: an identity (Basic user /
 * Cloudflare client ID) and a secret (Basic password / Cloudflare client secret). Persisted by
 * [name], so don't rename the entries.
 */
enum class ProxyAuthMode { NONE, BASIC, CLOUDFLARE_ACCESS }

fun ProxyAuthMode.toProxyAuth(identity: String, secret: String): ProxyAuth = when (this) {
    ProxyAuthMode.NONE -> ProxyAuth.None
    ProxyAuthMode.BASIC -> ProxyAuth.Basic(identity, secret)
    ProxyAuthMode.CLOUDFLARE_ACCESS -> ProxyAuth.CloudflareAccess(identity, secret)
}

/** The headers to add to the WebSocket handshake, in order. Call only on a valid [ProxyAuth] ([proxyAuthError] null). */
internal fun ProxyAuth.headers(): List<Pair<String, String>> = when (this) {
    ProxyAuth.None -> emptyList()
    // UTF-8 rather than OkHttp's ISO-8859-1 default, so non-Latin-1 characters aren't mangled.
    is ProxyAuth.Basic -> listOf("Authorization" to Credentials.basic(user, password, Charsets.UTF_8))
    is ProxyAuth.CloudflareAccess -> listOf(
        "CF-Access-Client-Id" to clientId.trim(),
        "CF-Access-Client-Secret" to clientSecret.trim(),
    )
}

/**
 * Why [this] can't be used, as a user-facing message, or null when it's fine. Checked before it
 * reaches [Request.Builder.header], which throws on characters a header can't carry.
 */
internal fun ProxyAuth.proxyAuthError(): String? = when (this) {
    ProxyAuth.None -> null
    is ProxyAuth.Basic -> when {
        user.isEmpty() || password.isEmpty() -> "Enter the reverse proxy username and password."
        ':' in user -> "The reverse proxy username can't contain \":\"."
        // Base64 would carry them, but no proxy expects control characters (e.g. a pasted newline).
        (user + password).any { it.isISOControl() } -> "The reverse proxy credentials contain an invalid character."
        else -> null
    }
    is ProxyAuth.CloudflareAccess -> when {
        clientId.isBlank() || clientSecret.isBlank() -> "Enter the Cloudflare Access client ID and secret."
        // Surrounding whitespace (a sloppy paste) is trimmed; anything else must be visible ASCII.
        !(clientId.trim() + clientSecret.trim()).all { it in '!'..'~' } ->
            "The Cloudflare Access client ID or secret contains an invalid character."
        else -> null
    }
}

/** A WebSocket handshake request for [uri] carrying [proxyAuth]'s headers. */
internal fun handshakeRequest(uri: String, proxyAuth: ProxyAuth): Request =
    Request.Builder().url(uri).apply {
        proxyAuth.headers().forEach { (name, value) -> header(name, value) }
    }.build()

/**
 * The server answered the WebSocket handshake with a plain HTTP response instead of switching
 * protocols — typically a reverse proxy refusing the request ([httpCode] 401/403) or sending its
 * login page. Not an [java.io.IOException] on purpose: the server *was* reached, so this must never
 * trigger the "local network permission" notice. [message] is user-facing.
 */
class ProxyRejectedException(val httpCode: Int, message: String) : Exception(message)

internal fun proxyRejectionMessage(httpCode: Int, proxyAuthConfigured: Boolean): String = when {
    (httpCode == 401 || httpCode == 403) && proxyAuthConfigured ->
        "The reverse proxy refused its credentials (HTTP $httpCode). Check them under \"Reverse proxy\"."
    httpCode == 401 || httpCode == 403 ->
        "Access refused (HTTP $httpCode), probably by a reverse proxy. If your server is behind " +
            "Pangolin, Cloudflare Access or similar, set its credentials under \"Reverse proxy\"."
    else ->
        "The server answered HTTP $httpCode instead of opening the connection. TrueNAS SCALE " +
            "25.04 or later is required; a reverse proxy may also be showing its login page or " +
            "blocking WebSockets."
}

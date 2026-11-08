package com.nasmanagerapp.data.network

/**
 * Holds the credentials of the current session, if any. [TrueNasRpcClient] re-authenticates with
 * them (`auth.login_ex`) whenever it has to reopen its WebSocket, so it needs both. [password] is
 * the account password, or an API key pasted in its place. [proxyAuth] goes through the reverse
 * proxy in front of the NAS, if any, on every (re)connection.
 */
class CredentialsStore {
    @Volatile
    var username: String? = null

    @Volatile
    var password: String? = null

    @Volatile
    var proxyAuth: ProxyAuth = ProxyAuth.None
}

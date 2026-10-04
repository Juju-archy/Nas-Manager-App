package com.nasmanagerapp

import android.app.Application
import com.nasmanagerapp.data.auth.SessionPreferences
import com.nasmanagerapp.data.auth.TrueNasAuthRepository
import com.nasmanagerapp.data.dashboard.DashboardRepository
import com.nasmanagerapp.data.network.CredentialsStore
import com.nasmanagerapp.data.network.NetworkModule
import com.nasmanagerapp.data.network.TrueNasUrl
import com.nasmanagerapp.data.theme.ThemePreferences
import okhttp3.OkHttpClient

class TrueNasApplication : Application() {

    private val credentialsStore: CredentialsStore by lazy { CredentialsStore() }

    private val okHttpClient: OkHttpClient by lazy {
        NetworkModule.createOkHttpClient(credentialsStore)
    }

    /**
     * Plain client (no Basic Auth interceptor) for external, unauthenticated resources — currently
     * just the app catalog's icon URLs (`https://media.sys.truenas.net/...`, see `APPS_TODO.md`),
     * which aren't served by the user's own TrueNAS and shouldn't receive its credentials.
     */
    val imageOkHttpClient: OkHttpClient by lazy { OkHttpClient() }

    val sessionPreferences: SessionPreferences by lazy {
        SessionPreferences(this)
    }

    val themePreferences: ThemePreferences by lazy {
        ThemePreferences(this)
    }

    val authRepository: TrueNasAuthRepository by lazy {
        TrueNasAuthRepository(okHttpClient, credentialsStore)
    }

    val dashboardRepository: DashboardRepository by lazy {
        DashboardRepository(okHttpClient) { TrueNasUrl.normalize(sessionPreferences.serverUrl) }
    }

    /** The user currently authenticated against the API, if any — read from the single source of truth ([CredentialsStore]) rather than [sessionPreferences], which only holds a username when "Stay logged in" was checked. */
    val currentUsername: String?
        get() = credentialsStore.username

    override fun onCreate() {
        super.onCreate()
        // Restore a "Stay logged in" session so requests are authenticated as soon as the
        // app starts, before the first screen (which may skip straight to HomeScreen) renders.
        if (sessionPreferences.isLoggedIn) {
            credentialsStore.username = sessionPreferences.username
            credentialsStore.password = sessionPreferences.password
        }
    }
}

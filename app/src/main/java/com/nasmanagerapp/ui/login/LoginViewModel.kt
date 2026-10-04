package com.nasmanagerapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.auth.SessionPreferences
import com.nasmanagerapp.data.auth.TrueNasAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: TrueNasAuthRepository,
    private val sessionPreferences: SessionPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            serverUrl = sessionPreferences.serverUrl,
        )
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onRememberMeChange(value: Boolean) {
        _uiState.update { it.copy(rememberMe = value) }
    }

    fun onAcceptHttpRisksChange(value: Boolean) {
        _uiState.update { it.copy(acceptHttpRisks = value, errorMessage = null) }
    }

    fun connect(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isLoading) return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.login(state.serverUrl, state.username, state.password, state.acceptHttpRisks)
                .onSuccess {
                    if (state.rememberMe) {
                        sessionPreferences.serverUrl = state.serverUrl
                        sessionPreferences.username = state.username
                        sessionPreferences.password = state.password
                        sessionPreferences.isLoggedIn = true
                    }
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Unknown error.")
                    }
                }
        }
    }
}

class LoginViewModelFactory(private val app: TrueNasApplication) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LoginViewModel(app.authRepository, app.sessionPreferences) as T
    }
}

package com.topntown.dms.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topntown.dms.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state for the phone+OTP login.
 *
 * Field-team devices are often on flaky networks, so we keep the state model small:
 * whatever the user has typed, a single boolean for "in flight", and one optional
 * error string. No multi-step flow — one screen, one submit.
 */
data class LoginUiState(
    val phone: String = "",
    val otp: String = "",
    val isOtpVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    /** Exactly 10 digits — matches how phone identifiers are stored in Supabase. */
    val isPhoneValid: Boolean get() = phone.length == 10 && phone.all(Char::isDigit)

    /** Supabase OTP length is 6 digits by default. */
    val isOtpValid: Boolean get() = otp.length == 6 && otp.all(Char::isDigit)

    val canSubmit: Boolean get() = isPhoneValid && isOtpValid && !isLoading
}

/**
 * Navigation events emitted as a one-shot Channel, not StateFlow. Using a
 * replayable flow here would cause the screen to re-navigate on configuration
 * change after a successful login.
 */
sealed interface LoginNavEvent {
    data object NavigateToHome : LoginNavEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navEvents = Channel<LoginNavEvent>(capacity = Channel.BUFFERED)
    val navEvents = _navEvents.receiveAsFlow()

    fun onPhoneChanged(value: String) {
        // Only allow digits; cap at 10. No country-code prefix — the raw 10 digits
        // are sent to Supabase GoTrue as-is.
        val digits = value.filter(Char::isDigit).take(10)
        _uiState.update { it.copy(phone = digits, error = null) }
    }

    fun onOtpChanged(value: String) {
        // Only allow digits; cap at 6.
        val digits = value.filter(Char::isDigit).take(6)
        _uiState.update { it.copy(otp = digits, error = null) }
    }

    fun toggleOtpVisibility() {
        _uiState.update { it.copy(isOtpVisible = !it.isOtpVisible) }
    }

    /**
     * Submit credentials to Supabase. The 10-digit phone and the 6-digit OTP
     * are passed straight through to [AuthRepository], which calls GoTrue's
     * phone OTP endpoint. On success we emit a nav event; on failure we render
     * a caller-friendly error below the button.
     */
    fun login() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            authRepository.signIn(state.phone, state.otp)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _navEvents.send(LoginNavEvent.NavigateToHome)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = friendlyError(e)) }
                }
        }
    }

    /**
     * Collapse GoTrue/Postgrest exception chains into something a field user can
     * act on. "Invalid credentials" and "Account inactive" are the two messages
     * the screen spec calls out by name; the rest are best-effort fallbacks.
     */
    private fun friendlyError(t: Throwable): String {
        // AuthRepository throws several pre-formed messages that are already
        // field-user-friendly — surface them verbatim instead of pattern-matching.
        val raw = t.message.orEmpty()
        if (raw.startsWith("This app is for distributors only")) return raw
        if (raw.startsWith("Account not set up")) return raw

        val msg = raw.lowercase()
        return when {
            "invalid" in msg && ("otp" in msg || "token" in msg) ->
                "Invalid OTP. Please check and try again."
            "expired" in msg ->
                "OTP has expired. Please request a new one."
            "invalid login" in msg || "invalid credentials" in msg ->
                "Invalid credentials"
            "not confirmed" in msg || "phone not confirmed" in msg ->
                "Account inactive. Please contact your administrator."
            "disabled" in msg || "banned" in msg ->
                "Account inactive. Please contact your administrator."
            "network" in msg || "unable to resolve" in msg || "timeout" in msg ||
                "failed to connect" in msg ->
                "No internet. Check your connection and try again."
            "rate" in msg || "too many" in msg ->
                "Too many attempts. Please wait a minute and try again."
            raw.isBlank() -> "Something went wrong. Please try again."
            else -> raw
        }
    }
}

package br.com.walletapp.desktop.data

import br.com.walletapp.contract.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The customer's session, **only in memory** (ADR-001, decision 5): closing the app forgets it, and
 * nothing is written to disk. The app observes [state] to go back to the login screen when it ends.
 */
class SessionStore {

    sealed interface State {
        data object LoggedOut : State
        data class LoggedIn(val session: Session) : State

        /** Ended by the server or by inactivity; [reason] is shown on the login screen. */
        data class Ended(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.LoggedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val token: String? get() = (_state.value as? State.LoggedIn)?.session?.token

    fun start(session: Session) {
        _state.value = State.LoggedIn(session)
    }

    fun expire(reason: String) {
        if (_state.value is State.LoggedIn) _state.value = State.Ended(reason)
    }

    fun logout() {
        _state.value = State.LoggedOut
    }
}

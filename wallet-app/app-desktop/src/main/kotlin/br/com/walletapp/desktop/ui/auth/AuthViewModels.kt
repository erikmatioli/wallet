package br.com.walletapp.desktop.ui.auth

import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.AppApiException
import br.com.walletapp.desktop.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Login. On success it only starts the [SessionStore]: the app watches the session and moves to the
 * home screen, so this ViewModel knows nothing about navigation.
 */
class LoginViewModel(private val api: AppApiClient, private val session: SessionStore, private val scope: CoroutineScope) {

    data class State(val cpf: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null) {
        val canSubmit get() = !busy && cpf.isNotBlank() && password.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onCpf(value: String) = _state.update { it.copy(cpf = value, error = null) }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit(): Job {
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                session.start(api.login(s.cpf, s.password))
                _state.value = State() // nothing typed stays in memory after the login
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, password = "", error = message(e)) }
            }
        }
    }
}

/** Opening an account: checks what can be checked here (the confirmation); app-api checks the rest. */
class SignupViewModel(private val api: AppApiClient, private val session: SessionStore, private val scope: CoroutineScope) {

    data class State(
        val cpf: String = "",
        val name: String = "",
        val password: String = "",
        val confirmation: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val canSubmit get() = !busy && cpf.isNotBlank() && name.isNotBlank() && password.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onCpf(value: String) = _state.update { it.copy(cpf = value, error = null) }
    fun onName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onConfirmation(value: String) = _state.update { it.copy(confirmation = value, error = null) }

    fun submit(): Job? {
        val s = _state.value
        if (s.password != s.confirmation) {
            _state.update { it.copy(error = "As senhas não são iguais.") }
            return null
        }
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                session.start(api.signup(s.cpf, s.name, s.password))
                _state.value = State()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = message(e)) }
            }
        }
    }
}

/** app-api's messages are written for the customer; anything else (network) gets a generic one. */
internal fun message(e: Exception): String =
    (e as? AppApiException)?.message ?: "Não foi possível falar com o servidor. Verifique a conexão e tente de novo."

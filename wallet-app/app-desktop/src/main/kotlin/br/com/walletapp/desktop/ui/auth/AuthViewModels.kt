package br.com.walletapp.desktop.ui.auth

import br.com.walletapp.contract.CodeSent
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.AppApiException
import br.com.walletapp.desktop.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The second step of login and signup (ADR-002): the code that arrived by email. [resendIn] counts down
 * the seconds until "Reenviar código" is offered again.
 */
data class CodeStep(val challengeId: String, val sentMessage: String, val code: String = "", val resendIn: Int = 0) {
    val canConfirm get() = code.trim().length == 6
    val canResend get() = resendIn == 0
}

/**
 * The countdown of [CodeStep.resendIn], shared by both ViewModels. One second at a time on [scope]; a new
 * countdown cancels the previous one.
 */
private class Countdown(private val scope: CoroutineScope, private val tick: (Int) -> Unit) {
    private var job: Job? = null

    fun start(seconds: Int) {
        job?.cancel()
        tick(seconds)
        job = scope.launch {
            var left = seconds
            while (left > 0) {
                delay(1_000)
                left--
                tick(left)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}

/**
 * Login: the CPF, then the code sent to the account's email. On success it only starts the [SessionStore]:
 * the app watches the session and moves to the home screen, so this ViewModel knows nothing about navigation.
 */
class LoginViewModel(private val api: AppApiClient, private val session: SessionStore, private val scope: CoroutineScope) {

    data class State(val cpf: String = "", val step: CodeStep? = null, val busy: Boolean = false, val error: String? = null) {
        val canSend get() = !busy && cpf.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val countdown = Countdown(scope) { left -> _state.update { it.copy(step = it.step?.copy(resendIn = left)) } }

    fun onCpf(value: String) = _state.update { it.copy(cpf = value, error = null) }

    fun onCode(value: String) = _state.update { it.copy(step = it.step?.copy(code = digits(value)), error = null) }

    /** Asks for a code - the first one, or again with "Reenviar código". */
    fun sendCode(): Job {
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                showCode(api.startLogin(s.cpf))
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    fun confirm(): Job? {
        val s = _state.value
        val step = s.step ?: return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                session.start(api.confirmLogin(step.challengeId, s.cpf, step.code.trim()))
                countdown.stop()
                _state.value = State() // nothing typed stays in memory after the login
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, step = it.step?.copy(code = ""), error = message(e)) }
            }
        }
    }

    /** Back to the CPF: another CPF, or the same one after a typo. */
    fun changeCpf() {
        countdown.stop()
        _state.update { it.copy(step = null, error = null) }
    }

    private fun showCode(sent: CodeSent) {
        _state.update { it.copy(busy = false, step = CodeStep(sent.challengeId, sent.message)) }
        countdown.start(sent.resendAfterSeconds)
    }

    private fun failed(e: Exception) {
        _state.update { it.copy(busy = false, error = message(e)) }
        waitFor(e)?.let { countdown.start(it) }
    }

    /** A "wait N seconds" from app-api restarts the countdown, so the button matches the server. */
    private fun waitFor(e: Exception): Int? =
        (e as? AppApiException)?.retryAfterSeconds?.toInt()?.takeIf { _state.value.step != null }
}

/**
 * Opening an account: name, CPF and email, then the code sent to that email - which proves the customer
 * owns it. app-api checks the data; here only that nothing is blank.
 */
class SignupViewModel(private val api: AppApiClient, private val session: SessionStore, private val scope: CoroutineScope) {

    data class State(
        val cpf: String = "",
        val name: String = "",
        val email: String = "",
        val step: CodeStep? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val canSend get() = !busy && cpf.isNotBlank() && name.isNotBlank() && email.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val countdown = Countdown(scope) { left -> _state.update { it.copy(step = it.step?.copy(resendIn = left)) } }

    fun onCpf(value: String) = _state.update { it.copy(cpf = value, error = null) }
    fun onName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onCode(value: String) = _state.update { it.copy(step = it.step?.copy(code = digits(value)), error = null) }

    fun sendCode(): Job {
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val sent = api.startSignup(s.cpf, s.name, s.email)
                _state.update { it.copy(busy = false, step = CodeStep(sent.challengeId, sent.message)) }
                countdown.start(sent.resendAfterSeconds)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = message(e)) }
                (e as? AppApiException)?.retryAfterSeconds?.toInt()
                    ?.takeIf { _state.value.step != null }?.let { countdown.start(it) }
            }
        }
    }

    fun confirm(): Job? {
        val s = _state.value
        val step = s.step ?: return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                session.start(api.confirmSignup(step.challengeId, step.code.trim(), s.cpf, s.name, s.email))
                countdown.stop()
                _state.value = State()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, step = it.step?.copy(code = ""), error = message(e)) }
            }
        }
    }

    /** Back to the form, to fix the email or the CPF; the next code is a new one. */
    fun changeData() {
        countdown.stop()
        _state.update { it.copy(step = null, error = null) }
    }
}

/** Only digits, at most 6: what the customer pastes from the email may come with spaces. */
private fun digits(value: String) = value.filter(Char::isDigit).take(6)

/** app-api's messages are written for the customer; anything else (network) gets a generic one. */
internal fun message(e: Exception): String =
    (e as? AppApiException)?.message ?: "Não foi possível falar com o servidor. Verifique a conexão e tente de novo."

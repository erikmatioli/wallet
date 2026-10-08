package br.com.walletapp.desktop.ui

import br.com.walletapp.desktop.data.SessionStore
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * When the customer last did something (ADR-001, decision 5). The window calls [touch] on every mouse or
 * keyboard event; nothing here knows about Compose, so it is tested with a virtual clock.
 *
 * @param now milliseconds of a monotonic clock; the test passes the coroutine test scheduler's
 */
class InactivityMonitor(val timeout: Duration = 5.minutes, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {

    @Volatile
    private var lastActivity = now()

    fun touch() {
        lastActivity = now()
    }

    fun expired(): Boolean = now() - lastActivity >= timeout.inWholeMilliseconds
}

/**
 * Runs while a customer is logged in: every [checkEvery], ends the session once the monitor says the
 * customer has been away for its timeout. Ending it clears the token from memory, and the app goes back
 * to the login screen with [REASON] - nothing of the account stays on the screen of an unattended machine.
 */
suspend fun watchInactivity(monitor: InactivityMonitor, session: SessionStore, checkEvery: Duration = 5.seconds) {
    monitor.touch() // a new session starts "active"
    while (session.state.value is SessionStore.State.LoggedIn) {
        delay(checkEvery)
        if (monitor.expired()) session.expire(REASON)
    }
}

const val REASON = "Sua sessão foi encerrada por inatividade. Entre de novo."

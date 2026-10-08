package br.com.walletapp.desktop.ui

import br.com.walletapp.desktop.data.SessionStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The 5 minutes run on the test's virtual clock: the whole test takes milliseconds. */
class InactivityTest {

    private fun loggedIn() = SessionStore().apply {
        start(Json.decodeFromString("""{"token":"t-1","expiresInSeconds":1800,"customerName":"Maria"}"""))
    }

    @Test
    fun `five minutes without any input end the session, with the reason for the login screen`() = runTest {
        val session = loggedIn()
        val monitor = InactivityMonitor(5.minutes) { testScheduler.currentTime }
        launch { watchInactivity(monitor, session, checkEvery = 5.seconds) }
        runCurrent()

        advanceTimeBy(4.minutes + 55.seconds)
        assertIs<SessionStore.State.LoggedIn>(session.state.value)

        advanceTimeBy(10.seconds)
        assertEquals(REASON, assertIs<SessionStore.State.Ended>(session.state.value).reason)
    }

    @Test
    fun `any input restarts the count`() = runTest {
        val session = loggedIn()
        val monitor = InactivityMonitor(5.minutes) { testScheduler.currentTime }
        launch { watchInactivity(monitor, session, checkEvery = 5.seconds) }
        runCurrent()

        advanceTimeBy(4.minutes)
        monitor.touch() // the customer moved the mouse
        advanceTimeBy(4.minutes)
        assertIs<SessionStore.State.LoggedIn>(session.state.value)

        advanceTimeBy(1.minutes + 5.seconds)
        assertIs<SessionStore.State.Ended>(session.state.value)
    }

    @Test
    fun `the watch stops by itself when the customer logs out`() = runTest {
        val session = loggedIn()
        val monitor = InactivityMonitor(5.minutes) { testScheduler.currentTime }
        val watch = launch { watchInactivity(monitor, session, checkEvery = 5.seconds) }
        runCurrent()

        session.logout()
        advanceTimeBy(6.seconds)

        assertEquals(true, watch.isCompleted)
        assertIs<SessionStore.State.LoggedOut>(session.state.value) // not turned into "Ended" afterwards
    }
}

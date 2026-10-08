package br.com.walletapp.desktop.ui

import br.com.walletapp.contract.StatementEntry
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.SessionStore
import br.com.walletapp.desktop.ui.account.HomeViewModel
import br.com.walletapp.desktop.ui.account.StatementViewModel
import br.com.walletapp.desktop.ui.auth.LoginViewModel
import br.com.walletapp.desktop.ui.auth.SignupViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ViewModels against an app-api played by a mock HTTP engine. The calls run on the engine's own
 * threads, so each test joins the Job a ViewModel returns instead of advancing virtual time.
 */
class ViewModelsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(session: SessionStore, handler: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        AppApiClient("http://app-api/", session, MockEngine { requests += it; handler(it) })

    private fun MockRequestHandleScope.ok(body: String) = respond(body, headers = json)
    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, message: String) =
        respond("""{"code":"$code","message":"$message"}""", status, json)

    private val session = """{"token":"t-1","expiresInSeconds":1800,"customerName":"Maria Silva"}"""
    private fun entry(seq: Long) =
        """{"transactionId":"tx-$seq","sequence":$seq,"type":"PIX_IN","credit":true,"amountCents":1000,"balanceAfterCents":5000,"occurredAt":"2026-10-07T13:00:00Z"}"""

    @Test
    fun `login starts the session, and forgets what was typed`() = runTest {
        val store = SessionStore()
        val vm = LoginViewModel(api(store) { ok(session) }, store, backgroundScope)
        vm.onCpf("529.982.247-25")
        vm.onPassword("senha1234")

        vm.submit().join()

        val loggedIn = assertIs<SessionStore.State.LoggedIn>(store.state.value)
        assertEquals("Maria Silva", loggedIn.session.customerName)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `a wrong password shows app-api's message and clears the password`() = runTest {
        val store = SessionStore()
        val vm = LoginViewModel(api(store) { error(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "CPF ou senha inválidos.") },
            store, backgroundScope)
        vm.onCpf("52998224725")
        vm.onPassword("errada1234")

        vm.submit().join()

        assertEquals("CPF ou senha inválidos.", vm.state.value.error)
        assertEquals("", vm.state.value.password)
        assertIs<SessionStore.State.LoggedOut>(store.state.value)
    }

    @Test
    fun `signup with different passwords never reaches the server`() {
        val store = SessionStore()
        val vm = SignupViewModel(api(store) { ok(session) }, store, kotlinx.coroutines.MainScope())
        vm.onCpf("52998224725"); vm.onName("Maria"); vm.onPassword("senha1234"); vm.onConfirmation("senha12345")

        assertNull(vm.submit())
        assertEquals("As senhas não são iguais.", vm.state.value.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `home loads the account and its last entries, sending the token`() = runTest {
        val store = SessionStore().apply { start(kotlinx.serialization.json.Json.decodeFromString(session)) }
        val vm = HomeViewModel(api(store) {
            if (it.url.encodedPath.endsWith("/me")) ok("""{"customerName":"Maria Silva","branch":"0001","accountNumber":"100","checkDigit":"1","status":"ACTIVE","balanceCents":123456}""")
            else ok("""{"entries":[${entry(2)},${entry(1)}]}""")
        }, backgroundScope)

        vm.load().join()

        val loaded = assertIs<HomeViewModel.State.Loaded>(vm.state.value)
        assertEquals(123456, loaded.me.balanceCents)
        assertEquals(2, loaded.recent.size)
        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer t-1" })
    }

    @Test
    fun `a failure on the home screen becomes a message, and does not take the app's scope down`() = runTest {
        val store = SessionStore().apply { start(kotlinx.serialization.json.Json.decodeFromString(session)) }
        val vm = HomeViewModel(api(store) {
            if (it.url.encodedPath.endsWith("/me")) error(HttpStatusCode.ServiceUnavailable, "TEMPORARILY_UNAVAILABLE", "Serviço indisponível no momento. Tente de novo.")
            else ok("""{"entries":[]}""")
        }, backgroundScope)

        vm.load().join()

        assertEquals("Serviço indisponível no momento. Tente de novo.", assertIs<HomeViewModel.State.Failed>(vm.state.value).message)
        assertTrue(backgroundScope.isActive) // the coroutineScope in load() kept the failure inside it
    }

    @Test
    fun `an expired session sends the app back to the login`() = runTest {
        val store = SessionStore().apply { start(kotlinx.serialization.json.Json.decodeFromString(session)) }
        val vm = HomeViewModel(api(store) { error(HttpStatusCode.Unauthorized, "SESSION_EXPIRED", "Sua sessão expirou. Entre de novo.") },
            backgroundScope)

        vm.load().join()

        assertEquals("Sua sessão expirou. Entre de novo.", assertIs<SessionStore.State.Ended>(store.state.value).reason)
    }

    @Test
    fun `statement pages with the cursor, and the detail needs no request`() = runTest {
        val store = SessionStore().apply { start(kotlinx.serialization.json.Json.decodeFromString(session)) }
        val vm = StatementViewModel(api(store) {
            if (it.url.parameters["before"] == null) ok("""{"entries":[${entry(3)},${entry(2)}],"nextBefore":2}""")
            else ok("""{"entries":[${entry(1)}]}""")
        }, backgroundScope)

        vm.loadMore()!!.join()
        vm.loadMore()!!.join()

        assertEquals(listOf(3L, 2L, 1L), vm.state.value.entries.map(StatementEntry::sequence))
        assertEquals("2", requests.last().url.parameters["before"])
        assertNull(vm.loadMore()) // no more pages: no request
        val calls = requests.size
        vm.open(vm.state.value.entries.first())
        assertEquals("tx-3", vm.state.value.selected?.transactionId)
        assertEquals(calls, requests.size)
    }

    @Test
    fun `money is written in reais from cents`() {
        assertEquals("R$ 1.234,56", Format.money(123456))
        assertEquals("R$ 0,05", Format.money(5))
        assertEquals("Pix recebido", Format.type("PIX_IN"))
    }
}

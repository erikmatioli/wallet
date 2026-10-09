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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val codeSent = """{"challengeId":"ch-1","message":"Se houver conta para este CPF, enviamos um código.","resendAfterSeconds":60}"""

    private fun bodyOf(r: HttpRequestData) = (r.body as TextContent).text

    @Test
    fun `login asks for the code, then the code starts the session and nothing typed stays`() = runTest {
        val store = SessionStore()
        val vm = LoginViewModel(api(store) {
            if (it.url.encodedPath.endsWith("/login/start")) ok(codeSent) else ok(session)
        }, store, backgroundScope)
        vm.onCpf("529.982.247-25")

        vm.sendCode().join()
        val step = vm.state.value.step!!
        assertEquals("Se houver conta para este CPF, enviamos um código.", step.sentMessage)
        assertFalse(step.canResend)

        vm.onCode(" 123 456 ")
        assertEquals("123456", vm.state.value.step!!.code)
        vm.confirm()!!.join()

        assertEquals("Maria Silva", assertIs<SessionStore.State.LoggedIn>(store.state.value).session.customerName)
        assertEquals(LoginViewModel.State(), vm.state.value)
        assertTrue(bodyOf(requests.last()).contains("\"challengeId\":\"ch-1\""))
    }

    @Test
    fun `a wrong code shows app-api's message, clears the code and stays on the code step`() = runTest {
        val store = SessionStore()
        val vm = LoginViewModel(api(store) {
            if (it.url.encodedPath.endsWith("/login/start")) ok(codeSent)
            else error(HttpStatusCode.Unauthorized, "INVALID_CODE", "Código incorreto. Confira o e-mail e tente de novo.")
        }, store, backgroundScope)
        vm.onCpf("52998224725")
        vm.sendCode().join()
        vm.onCode("000000")

        vm.confirm()!!.join()

        assertEquals("Código incorreto. Confira o e-mail e tente de novo.", vm.state.value.error)
        assertEquals("", vm.state.value.step!!.code)
        assertIs<SessionStore.State.LoggedOut>(store.state.value)
    }

    @Test
    fun `resend is offered after the countdown, and a wait from the server restarts it`() = runTest {
        val store = SessionStore()
        var calls = 0
        val vm = LoginViewModel(api(store) {
            if (++calls == 1) ok(codeSent)
            else respond("""{"code":"TOO_MANY_REQUESTS","message":"Aguarde 30 segundos para pedir outro código.","retryAfterSeconds":30}""",
                HttpStatusCode.TooManyRequests, json)
        }, store, backgroundScope)
        vm.onCpf("52998224725")
        vm.sendCode().join()
        assertEquals(60, vm.state.value.step!!.resendIn)

        testScheduler.advanceTimeBy(60_001)
        assertTrue(vm.state.value.step!!.canResend)

        vm.sendCode().join()
        assertEquals("Aguarde 30 segundos para pedir outro código.", vm.state.value.error)
        assertEquals(30, vm.state.value.step!!.resendIn)
    }

    @Test
    fun `signup sends the code to the email typed, then opens the account with the same data`() = runTest {
        val store = SessionStore()
        val vm = SignupViewModel(api(store) {
            if (it.url.encodedPath.endsWith("/signup/start")) ok(codeSent) else ok(session)
        }, store, backgroundScope)
        vm.onName("Maria Silva"); vm.onCpf("52998224725"); vm.onEmail("maria@example.com")

        vm.sendCode().join()
        assertTrue(bodyOf(requests.last()).contains("\"email\":\"maria@example.com\""))
        vm.onCode("123456")
        vm.confirm()!!.join()

        val confirm = bodyOf(requests.last())
        listOf("\"challengeId\":\"ch-1\"", "\"code\":\"123456\"", "\"email\":\"maria@example.com\"", "\"name\":\"Maria Silva\"")
            .forEach { assertTrue(confirm.contains(it), confirm) }
        assertIs<SessionStore.State.LoggedIn>(store.state.value)
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

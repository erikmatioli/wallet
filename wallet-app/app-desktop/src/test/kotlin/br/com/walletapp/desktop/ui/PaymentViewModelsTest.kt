package br.com.walletapp.desktop.ui

import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.SessionStore
import br.com.walletapp.desktop.ui.payments.PixViewModel
import br.com.walletapp.desktop.ui.payments.Step
import br.com.walletapp.desktop.ui.payments.TransferViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PaymentViewModelsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private val session = SessionStore().apply {
        start(Json.decodeFromString("""{"token":"t-1","expiresInSeconds":1800,"customerName":"Maria"}"""))
    }

    private fun api(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AppApiClient("http://app-api/", session, MockEngine { requests += it; handler(it) })

    private fun MockRequestHandleScope.ok(body: String) = respond(body, headers = json)

    private val destination = """{"holderName":"João Souza","branch":"0001","number":"200","checkDigit":"2"}"""

    @Test
    fun `amounts are read the way a Brazilian types them, in cents`() {
        assertEquals(123456, Format.parseCents("1.234,56"))
        assertEquals(1050, Format.parseCents("10,5"))
        assertEquals(1000, Format.parseCents("R$ 10"))
        listOf("", "0", "10,555", "abc", "10.5", "-3").forEach { assertNull(Format.parseCents(it), it) }
    }

    @Test
    fun `transfer - form, confirmation with who receives, then receipt`() = runTest {
        val vm = TransferViewModel(api {
            if (it.method == HttpMethod.Get) ok(destination)
            else ok("""{"transactionId":"tx-1","amountCents":15000,"occurredAt":"2026-10-07T13:00:00Z","destination":$destination}""")
        }, backgroundScope)
        vm.edit { it.copy(branch = "0001", number = "200", checkDigit = "2", amount = "150,00") }

        vm.review()!!.join()
        val confirm = assertIs<Step.Confirm<TransferViewModel.Preview>>(vm.state.value.step)
        assertEquals("João Souza", confirm.preview.destination.holderName)
        assertEquals(15000, confirm.preview.amountCents)

        vm.confirm()!!.join()
        assertEquals("tx-1", assertIs<Step.Done<*>>(vm.state.value.step).let { (it.receipt as br.com.walletapp.contract.TransferReceipt).transactionId })
        assertEquals(confirm.idempotencyKey, requests.last().headers["Idempotency-Key"])
    }

    @Test
    fun `a failed confirmation stays on the confirmation, and trying again repeats the same key`() = runTest {
        var attempts = 0
        val vm = TransferViewModel(api {
            if (it.method == HttpMethod.Get) ok(destination)
            else if (++attempts == 1) respond("""{"code":"TEMPORARILY_UNAVAILABLE","message":"Serviço indisponível no momento. Tente de novo."}""",
                HttpStatusCode.ServiceUnavailable, json)
            else ok("""{"transactionId":"tx-1","amountCents":100,"occurredAt":"2026-10-07T13:00:00Z","destination":$destination}""")
        }, backgroundScope)
        vm.edit { it.copy(branch = "0001", number = "200", checkDigit = "2", amount = "1") }
        vm.review()!!.join()

        vm.confirm()!!.join()
        assertEquals("Serviço indisponível no momento. Tente de novo.", vm.state.value.error)
        assertIs<Step.Confirm<*>>(vm.state.value.step)

        vm.confirm()!!.join()
        assertIs<Step.Done<*>>(vm.state.value.step)
        val keys = requests.filter { it.method == HttpMethod.Post }.map { it.headers["Idempotency-Key"] }
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1]) // the retry is the same transfer, not a second one
    }

    @Test
    fun `an invalid amount never reaches the server`() {
        val vm = TransferViewModel(api { ok(destination) }, kotlinx.coroutines.MainScope())
        vm.edit { it.copy(branch = "0001", number = "200", checkDigit = "2", amount = "dez reais") }

        assertNull(vm.review())
        assertEquals("Informe um valor válido, como 150,00.", vm.state.value.error)
        assertEquals(0, requests.size)
    }

    @Test
    fun `a Pix receipt follows the Pix until it is settled`() = runTest {
        var polls = 0
        val vm = PixViewModel(api {
            if (it.method == HttpMethod.Post) ok(pix("SENT"))
            else ok(pix(if (++polls < 2) "SENT" else "COMPLETED"))
        }, backgroundScope, pollEveryMillis = 1_000)
        vm.edit { it.copy(name = "Fulano", taxId = "11144477735", ispb = "99999999", branch = "0042", account = "1234565", amount = "15") }
        vm.review()

        vm.confirm()!!.join() // runTest skips the delays between the polls (virtual time)

        val done = assertIs<Step.Done<br.com.walletapp.contract.PixReceipt>>(vm.state.value.step)
        assertEquals("COMPLETED", done.receipt.status)
        assertEquals(2, polls)
    }

    @Test
    fun `a rejected Pix says why on the receipt`() = runTest {
        val vm = PixViewModel(api {
            if (it.method == HttpMethod.Post) ok(pix("SENT"))
            else ok(pix("REFUNDED", ""","reasonCode":"AC03","reasonMessage":"Conta do recebedor inexistente ou inválida.""""))
        }, backgroundScope)
        vm.edit { it.copy(name = "Fulano", taxId = "11144477735", ispb = "99999999", branch = "0042", account = "1234565", amount = "7,99") }
        vm.review()

        vm.confirm()!!.join()

        val done = assertIs<Step.Done<br.com.walletapp.contract.PixReceipt>>(vm.state.value.step)
        assertEquals("REFUNDED", done.receipt.status)
        assertEquals("Conta do recebedor inexistente ou inválida.", done.receipt.reasonMessage)
    }

    private fun pix(status: String, extra: String = "") =
        """{"endToEndId":"E1","status":"$status","amountCents":1500,"payeeName":"Fulano","payeeIspb":"99999999"$extra}"""
}

package br.com.walletapp.desktop.ui

import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleExecution
import br.com.walletapp.contract.ScheduleRequest
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.SessionStore
import br.com.walletapp.desktop.ui.payments.Step
import br.com.walletapp.desktop.ui.schedules.NewScheduleViewModel
import br.com.walletapp.desktop.ui.schedules.SchedulesViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ScheduleViewModelsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private val session = SessionStore().apply {
        start(Json.decodeFromString("""{"token":"t-1","expiresInSeconds":1800,"customerName":"Maria"}"""))
    }

    private fun api(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AppApiClient("http://app-api/", session, MockEngine { requests += it; handler(it) })

    private fun MockRequestHandleScope.ok(body: String) = respond(body, headers = json)

    private fun schedule(status: String = "ACTIVE", exec: String = "PENDING", canCancel: Boolean = true) = Schedule(
        "s1", "TRANSFER", status, "2026-10-09", 1_000, null, "João", "0001 / 200-2", "2026-10-07T13:00:00Z",
        canCancel, ScheduleExecution(exec, 0, "2026-10-09T09:00:00Z"),
    )

    private fun encode(s: Schedule) = Json.encodeToString(s)

    @Test
    fun `dates are typed day first and must exist`() {
        assertEquals("2026-10-09", Format.parseDate("09/10/2026"))
        listOf("31/02/2026", "2026-10-09", "9/10/26", "").forEach { assertNull(Format.parseDate(it), it) }
        assertEquals("09/10/2026", Format.date("2026-10-09"))
    }

    @Test
    fun `the situation reads as the console's, with the retry time in Brasilia`() {
        assertEquals("Agendado", Format.scheduleStatus(schedule()))
        // 15:00 UTC is 12:00 in Brasília.
        val retry = schedule().copy(execution = ScheduleExecution("PENDING", 1, "2026-10-09T15:00:00Z", "Saldo insuficiente."))
        assertEquals("Nova tentativa às 12:00", Format.scheduleStatus(retry))
        assertEquals("Pago", Format.scheduleStatus(schedule("COMPLETED", "EXECUTED")))
        assertEquals("Não pago", Format.scheduleStatus(schedule("COMPLETED", "FAILED")))
        assertEquals("Cancelado", Format.scheduleStatus(schedule("CANCELLED", "CANCELLED")))
    }

    @Test
    fun `a new transfer schedule - form, confirmation, created`() = runTest {
        val vm = NewScheduleViewModel(api { ok(encode(schedule())) }, backgroundScope)
        vm.edit { it.copy(date = "09/10/2026", amount = "10", branch = "0001", number = "200", checkDigit = "2") }

        vm.review()
        val confirm = assertIs<Step.Confirm<ScheduleRequest>>(vm.state.value.step)
        assertEquals("2026-10-09", confirm.preview.executeOn)
        assertEquals("TRANSFER", confirm.preview.type)
        assertNull(confirm.preview.pix)

        vm.confirm()!!.join()
        assertIs<Step.Done<*>>(vm.state.value.step)
        val sent = Json.decodeFromString<ScheduleRequest>((requests.single().body as TextContent).text)
        assertEquals(1_000, sent.amountCents)
        assertEquals(confirm.idempotencyKey, requests.single().headers["Idempotency-Key"])
    }

    @Test
    fun `a Pix schedule needs the payee, and a bad date stops in the form`() {
        val vm = NewScheduleViewModel(api { ok(encode(schedule())) }, kotlinx.coroutines.MainScope())
        vm.edit { it.copy(pix = true, date = "09/10/2026", amount = "10") }
        vm.review()
        assertEquals("Preencha os dados do recebedor.", vm.state.value.error)

        vm.edit { it.copy(date = "32/10/2026", name = "F", taxId = "1", ispb = "9", pixBranch = "1", account = "1") }
        vm.review()
        assertEquals("Informe a data no formato dd/mm/aaaa.", vm.state.value.error)

        vm.edit { it.copy(date = "09/10/2026") }
        vm.review()
        assertEquals("Informe o CPF (11 dígitos) ou o CNPJ (14 caracteres) do recebedor.", vm.state.value.error)
        assertEquals(0, requests.size)
    }

    @Test
    fun `cancelling from the detail updates the detail and the list`() = runTest {
        val vm = SchedulesViewModel(api {
            if (it.method == HttpMethod.Get) ok("[${encode(schedule())}]")
            else ok(encode(schedule("CANCELLED", "CANCELLED", canCancel = false)))
        }, backgroundScope)

        vm.load().join()
        vm.open(vm.state.value.schedules.single())
        vm.cancel()!!.join()

        assertEquals("CANCELLED", vm.state.value.selected?.status)
        assertEquals("CANCELLED", vm.state.value.schedules.single().status)
        assertEquals("/app/v1/schedules/s1/cancel", requests.last().url.encodedPath)
    }
}

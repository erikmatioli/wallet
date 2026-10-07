package br.com.walletscheduler.application

import br.com.walletscheduler.application.ExecutionService.Outcome
import br.com.walletscheduler.application.port.NotReadyYetException
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.PixOutcome
import br.com.walletscheduler.domain.BusinessRuleException
import br.com.walletscheduler.domain.DomainException
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.ScheduleStatus
import br.com.walletscheduler.domain.ScheduleType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Pix schedules (ADR-001, step 3): sent on the day, closed by wallet-pix's result event. */
class PixScheduleTest {

    private val f = Fixture()
    private val day = LocalDate.of(2026, 10, 8)

    private fun details(id: ScheduleId) = f.schedules.get(f.tenant, id)

    /** Runs the first window: the Pix goes out and waits for its result. Returns the attempt's key. */
    private fun sendOnTheDay(id: ScheduleId): String {
        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.AWAITING_RESULT)
        return details(id).attempts.single().idempotencyKey
    }

    @Test
    fun `a Pix schedule needs the payer's CPF to be the account holder's`() {
        assertThatThrownBy { f.pix(payerTaxId = "11144477735") }
            .isInstanceOf(BusinessRuleException::class.java)
            .extracting { (it as DomainException).code }.isEqualTo("PAYER_TAX_ID_MISMATCH")

        val schedule = f.pix().details.schedule
        assertThat(schedule.type).isEqualTo(ScheduleType.PIX)
        assertThat((schedule.destination as PixDestination).taxIdMasked).isEqualTo("***7735")
    }

    @Test
    fun `on the day the Pix is sent, and its settlement event completes the schedule`() {
        val schedule = f.pix(executeOn = day).details.schedule
        val key = sendOnTheDay(schedule.id)

        val sent = details(schedule.id)
        val pending = f.pix.pendingOf(key)
        assertThat(sent.execution.status).isEqualTo(ExecutionStatus.PROCESSING)
        assertThat(sent.execution.endToEndId).isEqualTo(pending.endToEndId)
        assertThat(sent.attempts.single().outcome).isNull() // awaiting, not finished

        val (tenant, outcome) = f.executions.onPixEvent(PixOutcome(key, settled = true, reasonCode = null))!!
        assertThat(tenant).isEqualTo(f.tenant)
        assertThat(outcome).isEqualTo(Outcome.EXECUTED)

        val done = details(schedule.id)
        assertThat(done.schedule.status).isEqualTo(ScheduleStatus.COMPLETED)
        assertThat(done.execution.status).isEqualTo(ExecutionStatus.EXECUTED)
        assertThat(done.execution.transactionId).isEqualTo(pending.transactionId) // the debit
        assertThat(done.execution.endToEndId).isEqualTo(pending.endToEndId)
    }

    @Test
    fun `a Pix the SPI rejects fails with the reason, and its redelivered event changes nothing`() {
        val schedule = f.pix(executeOn = day).details.schedule
        val key = sendOnTheDay(schedule.id)

        val event = PixOutcome(key, settled = false, reasonCode = "AC03")
        assertThat(f.executions.onPixEvent(event)!!.second).isEqualTo(Outcome.FAILED)
        assertThat(f.executions.onPixEvent(event)!!.second).isEqualTo(Outcome.ALREADY_RECORDED)

        val d = details(schedule.id)
        assertThat(d.execution.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(d.execution.failure!!.message).isEqualTo("Conta do recebedor inexistente ou inválida")
        assertThat(d.execution.endToEndId).isNotNull() // the customer can still trace the Pix that was rejected
    }

    @Test
    fun `an event that arrives before the send was recorded is handed back for later`() {
        val schedule = f.pix(executeOn = day).details.schedule
        f.moveTo(day, "06:00")
        val work = f.executions.claim().single() // claimed, wallet-pix already answered, not recorded yet

        assertThatThrownBy { f.executions.onPixEvent(PixOutcome(work.attempt.idempotencyKey, true, null)) }
            .isInstanceOf(NotReadyYetException::class.java)

        assertThat(f.executions.execute(work)).isEqualTo(Outcome.AWAITING_RESULT)
        assertThat(f.executions.onPixEvent(PixOutcome(work.attempt.idempotencyKey, true, null))!!.second)
            .isEqualTo(Outcome.EXECUTED)
        assertThat(details(schedule.id).execution.status).isEqualTo(ExecutionStatus.EXECUTED)
    }

    @Test
    fun `a lost event is recovered by asking wallet-pix again with the same key`() {
        val schedule = f.pix(executeOn = day).details.schedule
        val key = sendOnTheDay(schedule.id)
        val pending = f.pix.pendingOf(key)
        f.pix.settle(key, PaymentResult.Executed(pending.transactionId, pending.endToEndId)) // settled; event lost

        f.moveTo(day, "06:09")
        assertThat(f.runJob()).isEmpty() // still waiting for the event

        f.moveTo(day, "06:10")
        assertThat(f.runJob()).containsExactly(Outcome.EXECUTED)
        assertThat(f.pix.sendKeys).containsExactly(key, key) // asked again, never a second Pix
        assertThat(details(schedule.id).execution.transactionId).isEqualTo(pending.transactionId)
    }

    @Test
    fun `a Pix refused before sending for insufficient funds is retried in the next window`() {
        val schedule = f.pix(executeOn = day).details.schedule
        f.pix.answers += PaymentResult.Refused("INSUFFICIENT_FUNDS", "no money")

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.RETRY_LATER)
        assertThat(details(schedule.id).execution.nextAttemptAt).isEqualTo(f.at(day, "12:00"))
    }

    @Test
    fun `events about Pix this service did not send are ignored`() {
        assertThat(f.executions.onPixEvent(PixOutcome("someone-else-${UUID.randomUUID()}", true, null))).isNull()
    }
}

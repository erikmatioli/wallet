package br.com.walletscheduler.application

import br.com.walletscheduler.application.ExecutionService.Outcome
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.domain.AttemptOutcome
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.ScheduleStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ExecutionServiceTest {

    private val f = Fixture()
    private val day = LocalDate.of(2026, 10, 8)

    private fun details(id: br.com.walletscheduler.domain.ScheduleId) = f.schedules.get(f.tenant, id)

    @Test
    fun `nothing runs before the first window of the day`() {
        f.transfer(executeOn = day)

        f.moveTo(day, "05:59")
        assertThat(f.runJob()).isEmpty()
        assertThat(f.walletCore.transferKeys).isEmpty()
    }

    @Test
    fun `pays in the first window and records the transfer`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        val transferId = UUID.randomUUID()
        f.walletCore.answers += PaymentResult.Executed(transferId)

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.EXECUTED)

        val d = details(schedule.id)
        assertThat(d.schedule.status).isEqualTo(ScheduleStatus.COMPLETED)
        assertThat(d.execution.status).isEqualTo(ExecutionStatus.EXECUTED)
        assertThat(d.execution.transactionId).isEqualTo(transferId)
        assertThat(d.attempts).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(AttemptOutcome.EXECUTED)
            assertThat(it.idempotencyKey).isEqualTo("sched-${d.execution.id}-1")
        })
        // A second tick finds nothing to do.
        assertThat(f.runJob()).isEmpty()
    }

    @Test
    fun `insufficient funds is retried in the next windows and fails after the last one`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        f.walletCore.answers += PaymentResult.Refused("INSUFFICIENT_FUNDS", "no money")

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.RETRY_LATER)
        assertThat(details(schedule.id).execution.nextAttemptAt).isEqualTo(f.at(day, "12:00"))

        f.moveTo(day, "11:00")
        assertThat(f.runJob()).isEmpty() // waits for the window

        f.moveTo(day, "12:00")
        assertThat(f.runJob()).containsExactly(Outcome.RETRY_LATER)
        f.moveTo(day, "18:00")
        assertThat(f.runJob()).containsExactly(Outcome.FAILED)

        val d = details(schedule.id)
        assertThat(d.schedule.status).isEqualTo(ScheduleStatus.COMPLETED)
        assertThat(d.execution.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(d.execution.failure!!.message).isEqualTo("Saldo insuficiente no momento do pagamento")
        assertThat(d.attempts).extracting<Int> { it.number }.containsExactly(1, 2, 3)
        // Each attempt has its own key: a refused transfer moved nothing, so the next one is a new request.
        assertThat(f.walletCore.transferKeys).doesNotHaveDuplicates().hasSize(3)
    }

    @Test
    fun `money arriving between windows lets the retry pay`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        f.walletCore.answers += listOf(PaymentResult.Refused("INSUFFICIENT_FUNDS", null),
            PaymentResult.Executed(UUID.randomUUID()))

        f.moveTo(day, "06:00")
        f.runJob()
        f.moveTo(day, "12:00")
        assertThat(f.runJob()).containsExactly(Outcome.EXECUTED)

        val d = details(schedule.id)
        assertThat(d.execution.status).isEqualTo(ExecutionStatus.EXECUTED)
        assertThat(d.attempts).extracting<AttemptOutcome?> { it.outcome }
            .containsExactly(AttemptOutcome.REFUSED, AttemptOutcome.EXECUTED)
    }

    @Test
    fun `a reason that will not change during the day fails right away`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        f.walletCore.answers += PaymentResult.Refused("DESTINATION_ACCOUNT_NOT_FOUND", null)

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.FAILED)
        assertThat(details(schedule.id).execution.failure!!.retryToday).isFalse()
    }

    @Test
    fun `an unknown result is asked again with the same key and never pays twice`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        f.walletCore.answers += listOf(PaymentResult.Unknown("timeout"), PaymentResult.Executed(UUID.randomUUID()))

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).containsExactly(Outcome.UNKNOWN)
        assertThat(details(schedule.id).execution.status).isEqualTo(ExecutionStatus.PROCESSING)

        f.moveTo(day, "06:00:30")
        assertThat(f.runJob()).isEmpty() // still leased

        f.moveTo(day, "06:01")
        assertThat(f.runJob()).containsExactly(Outcome.EXECUTED)
        assertThat(f.walletCore.transferKeys).hasSize(2).containsOnly(f.walletCore.transferKeys.first())
        assertThat(details(schedule.id).attempts).hasSize(1) // the same attempt, not a second one
    }

    @Test
    fun `a crash after the claim is recovered when the lease expires, with the same key`() {
        val schedule = f.transfer(executeOn = day).details.schedule

        f.moveTo(day, "06:00")
        val claimed = f.executions.claim() // ... and the worker dies before calling wallet-core
        assertThat(claimed).hasSize(1)
        assertThat(f.runJob()).isEmpty()

        f.moveTo(day, "06:02")
        assertThat(f.runJob()).containsExactly(Outcome.EXECUTED)
        assertThat(f.walletCore.transferKeys).containsExactly(claimed.single().attempt.idempotencyKey)
        assertThat(details(schedule.id).execution.status).isEqualTo(ExecutionStatus.EXECUTED)
    }

    @Test
    fun `a day the service missed entirely fails instead of paying on another day`() {
        val schedule = f.transfer(executeOn = day).details.schedule

        f.moveTo(day.plusDays(1), "06:00")
        assertThat(f.runJob()).isEmpty()

        val d = details(schedule.id)
        assertThat(d.execution.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(d.execution.failure!!.code).isEqualTo("MISSED_DAY")
        assertThat(f.walletCore.transferKeys).isEmpty()
    }

    @Test
    fun `a cancelled schedule never runs`() {
        val schedule = f.transfer(executeOn = day).details.schedule
        f.schedules.cancel(f.tenant, schedule.id)

        f.moveTo(day, "06:00")
        assertThat(f.runJob()).isEmpty()
        assertThat(f.walletCore.transferKeys).isEmpty()
    }
}

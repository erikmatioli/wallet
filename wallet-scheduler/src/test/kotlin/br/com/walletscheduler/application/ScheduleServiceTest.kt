package br.com.walletscheduler.application

import br.com.walletscheduler.domain.BusinessRuleException
import br.com.walletscheduler.domain.ConflictException
import br.com.walletscheduler.domain.DomainException
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.NotFoundException
import br.com.walletscheduler.domain.ScheduleStatus
import br.com.walletscheduler.domain.TenantId
import br.com.walletscheduler.domain.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ScheduleServiceTest {

    private val f = Fixture()
    private val tomorrow = LocalDate.of(2026, 10, 8)

    private fun code(e: Throwable) = (e as DomainException).code

    @Test
    fun `creates a transfer for the first window of the day, with the destination as wallet-core knows it`() {
        val created = f.transfer(executeOn = tomorrow)

        val (schedule, execution) = created.details
        schedule.destination as br.com.walletscheduler.domain.TransferDestination
        assertThat(created.replayed).isFalse()
        assertThat(schedule.status).isEqualTo(ScheduleStatus.ACTIVE)
        assertThat(schedule.destination.accountId).isEqualTo(f.payee.id)
        assertThat(schedule.destination.holderName).isEqualTo("João")
        assertThat(execution.status).isEqualTo(ExecutionStatus.PENDING)
        assertThat(execution.nextAttemptAt).isEqualTo(f.at(tomorrow, "06:00"))
    }

    @Test
    fun `the payment date must be from tomorrow on`() {
        assertThatThrownBy { f.transfer(executeOn = LocalDate.of(2026, 10, 7)) }
            .isInstanceOf(ValidationException::class.java)
            .extracting(::code).isEqualTo("INVALID_EXECUTION_DATE")
    }

    @Test
    fun `refuses a payer that cannot pay and a destination that does not exist`() {
        f.walletCore.accounts[f.payer.id] = f.payer.copy(status = "BLOCKED")
        assertThatThrownBy { f.transfer() }.extracting(::code).isEqualTo("PAYER_ACCOUNT_NOT_ACTIVE")

        f.walletCore.accounts[f.payer.id] = f.payer
        assertThatThrownBy { f.transfer(destinationNumber = "9999999") }
            .isInstanceOf(NotFoundException::class.java)
            .extracting(::code).isEqualTo("DESTINATION_ACCOUNT_NOT_FOUND")
    }

    @Test
    fun `a tenant this service cannot pay for is refused at creation, not on the day`() {
        f.walletCore.servedClients.clear()
        assertThatThrownBy { f.transfer() }.extracting(::code).isEqualTo("TENANT_NOT_CONFIGURED")
    }

    @Test
    fun `same key replays and another payload with it is a conflict`() {
        val first = f.transfer(key = "k1")
        val again = f.transfer(key = "k1")

        assertThat(again.replayed).isTrue()
        assertThat(again.details.schedule.id).isEqualTo(first.details.schedule.id)
        assertThat(f.repository.schedules).hasSize(1)
        assertThatThrownBy { f.transfer(key = "k1", amountCents = 1) }
            .isInstanceOf(ConflictException::class.java)
            .extracting(::code).isEqualTo("IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `cancels until the day before, and not on the day`() {
        val keep = f.transfer(executeOn = tomorrow).details.schedule
        val cancel = f.transfer(executeOn = tomorrow).details.schedule

        f.moveTo(LocalDate.of(2026, 10, 7), "23:59")
        val cancelled = f.schedules.cancel(f.tenant, cancel.id)
        assertThat(cancelled.schedule.status).isEqualTo(ScheduleStatus.CANCELLED)
        assertThat(cancelled.execution.status).isEqualTo(ExecutionStatus.CANCELLED)

        f.moveTo(tomorrow, "00:00")
        assertThatThrownBy { f.schedules.cancel(f.tenant, keep.id) }
            .isInstanceOf(BusinessRuleException::class.java)
            .extracting(::code).isEqualTo("CANCELLATION_DEADLINE_PASSED")
    }

    @Test
    fun `a tenant never sees or cancels another tenant's schedule`() {
        val schedule = f.transfer().details.schedule
        val other = TenantId(UUID.randomUUID())

        assertThatThrownBy { f.schedules.get(other, schedule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { f.schedules.cancel(other, schedule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThat(f.schedules.listByPayer(other, f.payer.id)).isEmpty()
    }
}

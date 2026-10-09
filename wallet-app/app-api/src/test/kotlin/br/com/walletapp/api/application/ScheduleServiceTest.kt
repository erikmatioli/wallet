package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.ScheduleOutcome
import br.com.walletapp.api.application.port.ScheduleView
import br.com.walletapp.api.application.port.SchedulerGateway
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.NotFoundException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.PixPayee
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleExecution
import br.com.walletapp.contract.ScheduleRequest
import br.com.walletapp.contract.TransferTarget
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** wallet-scheduler as it really is: separates tenants, not customers. */
class FakeScheduler : SchedulerGateway {
    val byId = linkedMapOf<String, ScheduleView>()
    val creates = mutableListOf<Triple<AccountId, String, String>>() // payer, payer CPF, key
    var refuse: String? = null

    override fun list(payer: AccountId) = byId.values.filter { it.payer == payer }

    override fun get(id: String) = byId[id]

    override fun create(payer: AccountId, payerCpf: Cpf, request: ScheduleRequest, idempotencyKey: String): ScheduleOutcome {
        refuse?.let { return ScheduleOutcome.Refused(it) }
        creates += Triple(payer, payerCpf.digits, idempotencyKey)
        val view = ScheduleView(payer, schedule(UUID.randomUUID().toString(), request.executeOn))
        byId[view.schedule.id] = view
        return ScheduleOutcome.Done(view)
    }

    override fun cancel(id: String): ScheduleOutcome {
        refuse?.let { return ScheduleOutcome.Refused(it) }
        val v = byId.getValue(id)
        val cancelled = v.copy(schedule = v.schedule.copy(status = "CANCELLED",
            execution = v.schedule.execution.copy(status = "CANCELLED")))
        byId[id] = cancelled
        return ScheduleOutcome.Done(cancelled)
    }

    companion object {
        fun schedule(id: String, executeOn: String) = Schedule(id, "TRANSFER", "ACTIVE", executeOn, 1_000, null, "João",
            "0001 / 200-2", "2026-10-07T13:00:00Z", canCancel = false, execution = ScheduleExecution("PENDING", 0))
    }
}

class ScheduleServiceTest {

    private val core = FakeCore()
    private val logins = InMemoryLogins()
    private val scheduler = FakeScheduler()
    // 2026-10-07 22:00 in Brasília (01:00 UTC on the 8th): "today" must be the 7th, not the 8th.
    private val clock = Clock.fixed(Instant.parse("2026-10-08T01:00:00Z"), ZoneOffset.UTC)
    private val otp = FakeOtp()
    private val auth = AuthService(core, logins, otp, FakeTokens, StartThrottle(clock), clock)
    private val schedules = ScheduleService(scheduler, logins, clock)

    private val transfer = ScheduleRequest("TRANSFER", "2026-10-08", 1_000, transfer = TransferTarget("0001", "200", "2"))

    private fun customer(cpf: String): Pair<LoginId, AccountId> {
        auth.signUp(otp, cpf, "Cliente $cpf")
        val login = logins.find(Cpf.parse(cpf))!!
        return login.id to login.accountId!!
    }

    @Test
    fun `a schedule is created for the token's account, with the login's CPF and a customer key`() {
        val (login, account) = customer("52998224725")

        val created = schedules.create(login, account,
            ScheduleRequest("PIX", "2026-10-09", 500, pix = PixPayee("99999999", "0042", "1234565", "11144477735", "Fulano")), "k1")

        val (payer, cpf, key) = scheduler.creates.single()
        assertThat(payer).isEqualTo(account)
        assertThat(cpf).isEqualTo("52998224725")
        assertThat(key).startsWith("app-").isNotEqualTo("k1")
        assertThat(created.canCancel).isTrue()
    }

    @Test
    fun `a customer never sees nor cancels another customer's schedule`() {
        val (maria, mariaAccount) = customer("52998224725")
        val (_, anaAccount) = customer("39053344705")
        val mariaSchedule = schedules.create(maria, mariaAccount, transfer, "k1")

        assertThat(schedules.list(anaAccount)).isEmpty()
        assertThatThrownBy { schedules.get(anaAccount, mariaSchedule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { schedules.cancel(anaAccount, mariaSchedule.id) }.isInstanceOf(NotFoundException::class.java)
        assertThat(scheduler.byId.getValue(mariaSchedule.id).schedule.status).isEqualTo("ACTIVE")

        assertThat(schedules.cancel(mariaAccount, mariaSchedule.id).status).isEqualTo("CANCELLED")
    }

    @Test
    fun `cancel is offered until the day before, counted in Brasilia`() {
        val (login, account) = customer("52998224725")
        val tomorrow = schedules.create(login, account, transfer, "k1")
        scheduler.byId["today"] = ScheduleView(account, FakeScheduler.schedule("today", "2026-10-07"))

        assertThat(tomorrow.canCancel).isTrue()
        assertThat(schedules.get(account, "today").canCancel).isFalse()
    }

    @Test
    fun `invalid requests stop here and refusals come back in the customer's words`() {
        val (login, account) = customer("52998224725")

        assertThatThrownBy { schedules.create(login, account, transfer.copy(transfer = null), "k") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { schedules.create(login, account, transfer.copy(executeOn = "08/10/2026"), "k") }
            .isInstanceOf(ValidationException::class.java)

        scheduler.refuse = "INVALID_EXECUTION_DATE"
        assertThatThrownBy { schedules.create(login, account, transfer, "k") }
            .isInstanceOf(BusinessRuleException::class.java)
            .hasMessage("Escolha uma data a partir de amanhã, em até um ano.")
    }
}

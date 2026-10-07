package br.com.walletscheduler.application

import br.com.walletscheduler.application.port.CoreAccount
import br.com.walletscheduler.application.port.ScheduleDetails
import br.com.walletscheduler.application.port.ScheduleRepository
import br.com.walletscheduler.application.port.StoredRequest
import br.com.walletscheduler.application.port.Transactions
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.PixPayments
import br.com.walletscheduler.application.port.WalletCore
import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.Attempt
import br.com.walletscheduler.domain.Execution
import br.com.walletscheduler.domain.ExecutionId
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.ExecutionWindows
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.Schedule
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.TenantId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test moves by hand, to reach the windows of the payment day. */
class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

/** wallet-core as the tests need it: known accounts, and the answer each transfer gets. */
class FakeWalletCore : WalletCore {
    val accounts = mutableMapOf<AccountId, CoreAccount>()
    val servedClients = mutableSetOf("demo-tenant")

    /** Answers given in order to the next transfers (the last one repeats); Executed by default. */
    val answers = ArrayDeque<PaymentResult>()

    /** Every call, so a test can check the idempotency key of each attempt. */
    val transferKeys = mutableListOf<String>()

    /** Transfers made, by key: a repeated key returns the original, like wallet-core. */
    val transfersByKey = mutableMapOf<String, UUID>()

    fun account(holder: String, number: String, status: String = "ACTIVE"): CoreAccount =
        CoreAccount(AccountId(UUID.randomUUID()), "0001", number, "5", status, holder).also { accounts[it.id] = it }

    override fun serves(clientId: String) = clientId in servedClients

    override fun findAccount(clientId: String, accountId: AccountId) = accounts[accountId]

    override fun findAccountByNumber(clientId: String, branch: String, number: String, checkDigit: String) =
        accounts.values.firstOrNull { it.branch == branch && it.number == number && it.checkDigit == checkDigit }

    /** CPF/CNPJ of each account's holder, for the holder check. */
    val holders = mutableMapOf<AccountId, String>()

    override fun checkHolder(clientId: String, account: CoreAccount, taxId: String) =
        if (holders[account.id] == taxId) "VALID" else "TAX_ID_MISMATCH"

    override fun transfer(clientId: String, from: AccountId, to: AccountId, amountCents: Long, description: String?,
                          idempotencyKey: String): PaymentResult {
        transferKeys += idempotencyKey
        transfersByKey[idempotencyKey]?.let { return PaymentResult.Executed(it) }
        val answer = if (answers.size > 1) answers.removeFirst() else answers.firstOrNull() ?: PaymentResult.Executed(UUID.randomUUID())
        if (answer is PaymentResult.Executed) transfersByKey[idempotencyKey] = answer.transactionId!!
        return answer
    }
}

/**
 * wallet-pix as the tests need it: answers given in order (the last one repeats), SENT by default.
 * A repeated key gets the payment as it is now, like wallet-pix: [settle] changes what it answers.
 */
class FakePix : PixPayments {
    val answers = ArrayDeque<PaymentResult>()
    val sendKeys = mutableListOf<String>()
    private val byKey = mutableMapOf<String, PaymentResult>()

    override fun send(clientId: String, payerAccountId: AccountId, payerTaxId: String, payee: PixDestination,
                      amountCents: Long, description: String?, idempotencyKey: String): PaymentResult {
        sendKeys += idempotencyKey
        byKey[idempotencyKey]?.let { return it }
        val answer = if (answers.size > 1) answers.removeFirst() else answers.firstOrNull()
            ?: PaymentResult.Pending("E12345678202610081200${idempotencyKey.hashCode().toUInt().toString().padStart(11, '0').take(11)}",
                UUID.randomUUID())
        if (answer !is PaymentResult.Refused) byKey[idempotencyKey] = answer
        return answer
    }

    /** What wallet-pix answers from now on for [key]: the Pix settled (or was refunded) in the meantime. */
    fun settle(key: String, result: PaymentResult) {
        byKey[key] = result
    }

    fun pendingOf(key: String) = byKey[key] as PaymentResult.Pending
}

object DirectTransactions : Transactions {
    override fun <T> inTransaction(work: () -> T): T = work()
}

/** Single-threaded stand-in for the JDBC repository, with the same contract. */
class InMemoryScheduleRepository : ScheduleRepository {
    val schedules = linkedMapOf<ScheduleId, Schedule>()
    val executions = linkedMapOf<ScheduleId, Execution>()
    val attempts = mutableListOf<Attempt>()
    private val requests = mutableMapOf<Pair<TenantId, String>, StoredRequest>()

    override fun insert(schedule: Schedule, execution: Execution, idempotencyKey: String, fingerprint: String): Boolean {
        val key = schedule.tenantId to idempotencyKey
        if (key in requests) return false
        requests[key] = StoredRequest(schedule.id, fingerprint)
        schedules[schedule.id] = schedule
        executions[schedule.id] = execution
        return true
    }

    override fun findRequest(tenantId: TenantId, idempotencyKey: String) = requests[tenantId to idempotencyKey]

    override fun find(tenantId: TenantId, id: ScheduleId): ScheduleDetails? {
        val s = schedules[id]?.takeIf { it.tenantId == tenantId } ?: return null
        val e = executions.getValue(id)
        return ScheduleDetails(s, e, attempts.filter { it.executionId == e.id }.sortedBy { it.number })
    }

    override fun listByPayer(tenantId: TenantId, payerAccountId: AccountId) =
        schedules.values.filter { it.tenantId == tenantId && it.payerAccountId == payerAccountId }
            .sortedByDescending { it.executeOn }
            .mapNotNull { find(tenantId, it.id) }

    override fun lock(tenantId: TenantId, id: ScheduleId) =
        schedules[id]?.takeIf { it.tenantId == tenantId }?.let { it to executions.getValue(id) }

    override fun lockDueExecutions(now: Instant, limit: Int) = executions.values.filter {
        (it.status == ExecutionStatus.PENDING && !it.nextAttemptAt!!.isAfter(now)) ||
            (it.status == ExecutionStatus.PROCESSING && !it.leaseUntil!!.isAfter(now))
    }.take(limit)

    override fun findSchedule(id: ScheduleId) = schedules.getValue(id)

    override fun findOpenAttempt(executionId: ExecutionId) =
        attempts.firstOrNull { it.executionId == executionId && it.finishedAt == null }

    override fun findAttemptByKey(idempotencyKey: String) = attempts.firstOrNull { it.idempotencyKey == idempotencyKey }

    override fun findExecution(id: ExecutionId) = executions.values.first { it.id == id }

    override fun update(schedule: Schedule) {
        schedules[schedule.id] = schedule
    }

    override fun update(execution: Execution) {
        executions[execution.scheduleId] = execution
    }

    override fun insert(attempt: Attempt) {
        attempts += attempt
    }

    override fun update(attempt: Attempt) {
        attempts.replaceAll { if (it.executionId == attempt.executionId && it.number == attempt.number) attempt else it }
    }
}

/** Everything wired together, starting on 2026-10-07 at 10:00 in Brasília. */
class Fixture {
    val windows = ExecutionWindows()
    val clock = MutableClock(at(LocalDate.of(2026, 10, 7), "10:00"))
    val walletCore = FakeWalletCore()
    val pix = FakePix()
    val repository = InMemoryScheduleRepository()
    val schedules = ScheduleService(walletCore, repository, DirectTransactions, windows, clock)
    val executions = ExecutionService(walletCore, pix, repository, DirectTransactions, windows, clock)
    val tenant = TenantId(UUID.randomUUID())

    val payer = walletCore.account("Maria", "1000001").also { walletCore.holders[it.id] = PAYER_CPF }
    val payee = walletCore.account("João", "2000002")

    /** An account at another institution: only wallet-pix reaches it. */
    val pixPayee = PixDestination("99999999", "0042", "12345678", "11144477735", "Fulano Externo")

    fun at(date: LocalDate, time: String): Instant =
        date.atTime(java.time.LocalTime.parse(time)).atZone(windows.zone).toInstant()

    fun moveTo(date: LocalDate, time: String) {
        clock.now = at(date, time)
    }

    fun transfer(
        executeOn: LocalDate = LocalDate.of(2026, 10, 8),
        amountCents: Long = 10_000,
        key: String = "key-${UUID.randomUUID()}",
        destinationNumber: String = payee.number,
    ) = schedules.createTransfer(
        ScheduleService.CreateTransfer(common(executeOn, amountCents, key), "0001", destinationNumber, "5"),
    )

    fun pix(
        executeOn: LocalDate = LocalDate.of(2026, 10, 8),
        amountCents: Long = 5_000,
        key: String = "key-${UUID.randomUUID()}",
        payerTaxId: String = PAYER_CPF,
    ) = schedules.createPix(ScheduleService.CreatePix(common(executeOn, amountCents, key), payerTaxId, pixPayee))

    private fun common(executeOn: LocalDate, amountCents: Long, key: String) =
        ScheduleService.Common(tenant, "demo-tenant", payer.id, amountCents, "aluguel", executeOn, key)

    companion object {
        const val PAYER_CPF = "52998224725"
    }

    /** One tick of the job: claim what is due and execute it. */
    fun runJob(): List<ExecutionService.Outcome> = executions.claim().map { executions.execute(it) }
}

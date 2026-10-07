package br.com.walletscheduler.adapter.out.persistence

import br.com.walletscheduler.application.port.ScheduleDetails
import br.com.walletscheduler.application.port.ScheduleRepository
import br.com.walletscheduler.application.port.StoredRequest
import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.Attempt
import br.com.walletscheduler.domain.AttemptOutcome
import br.com.walletscheduler.domain.Destination
import br.com.walletscheduler.domain.Execution
import br.com.walletscheduler.domain.ExecutionId
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.FailureReasons
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.Schedule
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.ScheduleStatus
import br.com.walletscheduler.domain.ScheduleType
import br.com.walletscheduler.domain.TenantId
import br.com.walletscheduler.domain.TransferDestination
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every API query filters by tenant_id: this database has no Row Level Security, because the job has
 * to see the executions of every tenant (ADR-001, decision 8). The job's queries are the only ones
 * without the filter.
 */
@Repository
class JdbcScheduleRepository(private val jdbc: JdbcClient) : ScheduleRepository {

    override fun insert(schedule: Schedule, execution: Execution, idempotencyKey: String, fingerprint: String): Boolean {
        val transfer = schedule.destination as? TransferDestination
        val pix = schedule.destination as? PixDestination
        val inserted = jdbc.sql(
            """
            INSERT INTO schedule (id, tenant_id, client_id, idempotency_key, fingerprint, type, payer_account_id,
                                  destination_branch, destination_number, destination_check_digit,
                                  destination_account_id, destination_holder_name, payer_tax_id, pix_payee_ispb,
                                  pix_payee_branch, pix_payee_account, pix_payee_tax_id, amount_cents, description,
                                  execute_on, status, created_at, cancelled_at)
            VALUES (:id, :tenant, :client, :key, :fingerprint, :type, :payer, :branch, :number, :digit,
                    :destination, :holder, :payerTaxId, :pixIspb, :pixBranch, :pixAccount, :pixTaxId, :amount,
                    :description, :executeOn, :status, :createdAt, NULL)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """.trimIndent(),
        )
            .param("id", schedule.id.value)
            .param("tenant", schedule.tenantId.value)
            .param("client", schedule.clientId)
            .param("key", idempotencyKey)
            .param("fingerprint", fingerprint)
            .param("type", schedule.type.name)
            .param("payer", schedule.payerAccountId.value)
            .param("branch", transfer?.branch, Types.VARCHAR)
            .param("number", transfer?.number, Types.VARCHAR)
            .param("digit", transfer?.checkDigit, Types.VARCHAR)
            .param("destination", transfer?.accountId?.value, Types.OTHER)
            .param("holder", schedule.destination.holderName)
            .param("payerTaxId", schedule.payerTaxId, Types.VARCHAR)
            .param("pixIspb", pix?.ispb, Types.VARCHAR)
            .param("pixBranch", pix?.branch, Types.VARCHAR)
            .param("pixAccount", pix?.accountNumber, Types.VARCHAR)
            .param("pixTaxId", pix?.taxId, Types.VARCHAR)
            .param("amount", schedule.amountCents)
            .param("description", schedule.description, Types.VARCHAR)
            .param("executeOn", schedule.executeOn)
            .param("status", schedule.status.name)
            .param("createdAt", ts(schedule.createdAt))
            .update()
        if (inserted == 0) return false
        jdbc.sql(
            """
            INSERT INTO schedule_execution (id, schedule_id, tenant_id, execute_on, status, attempt_count,
                                            next_attempt_at, lease_until, transaction_id, end_to_end_id,
                                            failure_code, failure_message, updated_at)
            VALUES (:id, :schedule, :tenant, :executeOn, :status, :attempts, :next, :lease, :tx, :e2e,
                    :failureCode, :failureMessage, :updatedAt)
            """.trimIndent(),
        ).executionParams(execution).param("schedule", execution.scheduleId.value)
            .param("tenant", execution.tenantId.value).param("executeOn", execution.executeOn)
            .update()
        return true
    }

    override fun findRequest(tenantId: TenantId, idempotencyKey: String): StoredRequest? =
        jdbc.sql("SELECT id, fingerprint FROM schedule WHERE tenant_id = :tenant AND idempotency_key = :key")
            .param("tenant", tenantId.value)
            .param("key", idempotencyKey)
            .query { rs, _ -> StoredRequest(ScheduleId(rs.uuid("id")), rs.getString("fingerprint")) }
            .optional().orElse(null)

    override fun find(tenantId: TenantId, id: ScheduleId): ScheduleDetails? =
        details("s.tenant_id = :tenant AND s.id = :id") { it.param("tenant", tenantId.value).param("id", id.value) }
            .firstOrNull()

    override fun listByPayer(tenantId: TenantId, payerAccountId: AccountId): List<ScheduleDetails> =
        details("s.tenant_id = :tenant AND s.payer_account_id = :payer") {
            it.param("tenant", tenantId.value).param("payer", payerAccountId.value)
        }

    override fun lock(tenantId: TenantId, id: ScheduleId): Pair<Schedule, Execution>? =
        jdbc.sql(
            """
            SELECT $SCHEDULE_COLUMNS, $EXECUTION_COLUMNS
              FROM schedule s JOIN schedule_execution e ON e.schedule_id = s.id
             WHERE s.tenant_id = :tenant AND s.id = :id
               FOR UPDATE
            """.trimIndent(),
        )
            .param("tenant", tenantId.value)
            .param("id", id.value)
            .query { rs, _ -> schedule(rs) to execution(rs) }
            .optional().orElse(null)

    override fun lockDueExecutions(now: Instant, limit: Int): List<Execution> =
        jdbc.sql(
            """
            SELECT $EXECUTION_COLUMNS
              FROM schedule_execution e
             WHERE (e.status = 'PENDING' AND e.next_attempt_at <= :now)
                OR (e.status = 'PROCESSING' AND e.lease_until <= :now)
             ORDER BY coalesce(e.next_attempt_at, e.lease_until)
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        )
            .param("now", ts(now))
            .param("limit", limit)
            .query { rs, _ -> execution(rs) }
            .list()

    override fun findSchedule(id: ScheduleId): Schedule =
        jdbc.sql("SELECT $SCHEDULE_COLUMNS FROM schedule s WHERE s.id = :id")
            .param("id", id.value)
            .query { rs, _ -> schedule(rs) }
            .single()

    override fun findAttemptByKey(idempotencyKey: String): Attempt? =
        jdbc.sql("SELECT $ATTEMPT_COLUMNS FROM schedule_attempt a WHERE a.idempotency_key = :key")
            .param("key", idempotencyKey)
            .query { rs, _ -> attempt(rs) }
            .optional().orElse(null)

    override fun findExecution(id: ExecutionId): Execution =
        jdbc.sql("SELECT $EXECUTION_COLUMNS FROM schedule_execution e WHERE e.id = :id")
            .param("id", id.value)
            .query { rs, _ -> execution(rs) }
            .single()

    override fun findOpenAttempt(executionId: ExecutionId): Attempt? =
        jdbc.sql("SELECT $ATTEMPT_COLUMNS FROM schedule_attempt a WHERE a.execution_id = :id AND a.finished_at IS NULL")
            .param("id", executionId.value)
            .query { rs, _ -> attempt(rs) }
            .optional().orElse(null)

    override fun update(schedule: Schedule) {
        jdbc.sql("UPDATE schedule SET status = :status, cancelled_at = :cancelledAt WHERE id = :id")
            .param("status", schedule.status.name)
            .param("cancelledAt", schedule.cancelledAt?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("id", schedule.id.value)
            .update()
    }

    override fun update(execution: Execution) {
        jdbc.sql(
            """
            UPDATE schedule_execution
               SET status = :status, attempt_count = :attempts, next_attempt_at = :next, lease_until = :lease,
                   transaction_id = :tx, end_to_end_id = :e2e, failure_code = :failureCode,
                   failure_message = :failureMessage,
                   updated_at = :updatedAt
             WHERE id = :id
            """.trimIndent(),
        ).executionParams(execution).update()
    }

    override fun insert(attempt: Attempt) {
        jdbc.sql(
            """
            INSERT INTO schedule_attempt (execution_id, number, idempotency_key, started_at, finished_at, outcome,
                                          reason_code, reason_message, transaction_id, end_to_end_id)
            VALUES (:execution, :number, :key, :startedAt, :finishedAt, :outcome, :reasonCode, :reasonMessage, :tx,
                    :e2e)
            """.trimIndent(),
        ).attemptParams(attempt).update()
    }

    override fun update(attempt: Attempt) {
        jdbc.sql(
            """
            UPDATE schedule_attempt
               SET finished_at = :finishedAt, outcome = :outcome, reason_code = :reasonCode,
                   reason_message = :reasonMessage, transaction_id = :tx, end_to_end_id = :e2e
             WHERE execution_id = :execution AND number = :number
            """.trimIndent(),
        ).attemptParams(attempt).update()
    }

    // ------------------------------------------------------------------ reads with attempts

    private fun details(where: String, bind: (JdbcClient.StatementSpec) -> JdbcClient.StatementSpec): List<ScheduleDetails> {
        val rows = bind(
            jdbc.sql(
                """
                SELECT $SCHEDULE_COLUMNS, $EXECUTION_COLUMNS
                  FROM schedule s JOIN schedule_execution e ON e.schedule_id = s.id
                 WHERE $where
                 ORDER BY s.execute_on DESC, s.created_at DESC
                """.trimIndent(),
            ),
        ).query { rs, _ -> schedule(rs) to execution(rs) }.list()
        if (rows.isEmpty()) return emptyList()
        // One query for the attempts of the whole list, not one per schedule.
        val attempts = jdbc.sql("SELECT $ATTEMPT_COLUMNS FROM schedule_attempt a WHERE a.execution_id IN (:ids) ORDER BY a.number")
            .param("ids", rows.map { it.second.id.value })
            .query { rs, _ -> attempt(rs) }
            .list()
            .groupBy { it.executionId }
        return rows.map { (s, e) -> ScheduleDetails(s, e, attempts[e.id].orEmpty()) }
    }

    // ------------------------------------------------------------------ mapping

    private fun JdbcClient.StatementSpec.executionParams(e: Execution) = this
        .param("id", e.id.value)
        .param("status", e.status.name)
        .param("attempts", e.attemptCount)
        .param("next", e.nextAttemptAt?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("lease", e.leaseUntil?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("tx", e.transactionId, Types.OTHER)
        .param("e2e", e.endToEndId, Types.VARCHAR)
        .param("failureCode", e.failure?.code, Types.VARCHAR)
        .param("failureMessage", e.failure?.message, Types.VARCHAR)
        .param("updatedAt", ts(e.updatedAt))

    private fun JdbcClient.StatementSpec.attemptParams(a: Attempt) = this
        .param("execution", a.executionId.value)
        .param("number", a.number)
        .param("key", a.idempotencyKey)
        .param("startedAt", ts(a.startedAt))
        .param("finishedAt", a.finishedAt?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("outcome", a.outcome?.name, Types.VARCHAR)
        .param("reasonCode", a.reason?.code, Types.VARCHAR)
        .param("reasonMessage", a.reason?.message, Types.VARCHAR)
        .param("tx", a.transactionId, Types.OTHER)
        .param("e2e", a.endToEndId, Types.VARCHAR)

    private companion object {
        const val SCHEDULE_COLUMNS = """s.id, s.tenant_id, s.client_id, s.type, s.payer_account_id, s.destination_branch,
            s.destination_number, s.destination_check_digit, s.destination_account_id, s.destination_holder_name,
            s.payer_tax_id, s.pix_payee_ispb, s.pix_payee_branch, s.pix_payee_account, s.pix_payee_tax_id,
            s.amount_cents, s.description, s.execute_on, s.status AS schedule_status, s.created_at, s.cancelled_at"""

        const val EXECUTION_COLUMNS = """e.id AS execution_id, e.schedule_id, e.tenant_id AS execution_tenant_id,
            e.execute_on AS execution_date, e.status AS execution_status, e.attempt_count, e.next_attempt_at,
            e.lease_until, e.transaction_id, e.end_to_end_id AS execution_e2e, e.failure_code, e.failure_message,
            e.updated_at"""

        const val ATTEMPT_COLUMNS = """a.execution_id, a.number, a.idempotency_key, a.started_at, a.finished_at, a.outcome,
            a.reason_code, a.reason_message, a.transaction_id, a.end_to_end_id"""

        // OffsetDateTime is what the PostgreSQL driver maps to timestamptz, also when bound as null.
        fun ts(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

        fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

        fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

        fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

        fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

        fun ResultSet.date(column: String): LocalDate = getObject(column, LocalDate::class.java)

        fun schedule(rs: ResultSet) = Schedule(
            id = ScheduleId(rs.uuid("id")),
            tenantId = TenantId(rs.uuid("tenant_id")),
            clientId = rs.getString("client_id"),
            payerAccountId = AccountId(rs.uuid("payer_account_id")),
            payerTaxId = rs.getString("payer_tax_id"),
            destination = destination(rs),
            amountCents = rs.getLong("amount_cents"),
            description = rs.getString("description"),
            executeOn = rs.date("execute_on"),
            status = ScheduleStatus.valueOf(rs.getString("schedule_status")),
            createdAt = rs.instant("created_at"),
            cancelledAt = rs.instantOrNull("cancelled_at"),
        )

        fun destination(rs: ResultSet): Destination = when (ScheduleType.valueOf(rs.getString("type"))) {
            ScheduleType.TRANSFER -> TransferDestination(
                branch = rs.getString("destination_branch"),
                number = rs.getString("destination_number"),
                checkDigit = rs.getString("destination_check_digit"),
                accountId = AccountId(rs.uuid("destination_account_id")),
                holderName = rs.getString("destination_holder_name"),
            )
            ScheduleType.PIX -> PixDestination(
                ispb = rs.getString("pix_payee_ispb"),
                branch = rs.getString("pix_payee_branch"),
                accountNumber = rs.getString("pix_payee_account"),
                taxId = rs.getString("pix_payee_tax_id"),
                holderName = rs.getString("destination_holder_name"),
            )
        }

        fun execution(rs: ResultSet) = Execution(
            id = ExecutionId(rs.uuid("execution_id")),
            scheduleId = ScheduleId(rs.uuid("schedule_id")),
            tenantId = TenantId(rs.uuid("execution_tenant_id")),
            executeOn = rs.date("execution_date"),
            status = ExecutionStatus.valueOf(rs.getString("execution_status")),
            attemptCount = rs.getInt("attempt_count"),
            nextAttemptAt = rs.instantOrNull("next_attempt_at"),
            leaseUntil = rs.instantOrNull("lease_until"),
            transactionId = rs.uuidOrNull("transaction_id"),
            endToEndId = rs.getString("execution_e2e"),
            failure = rs.getString("failure_code")?.let { code ->
                FailureReasons.of(code).copy(message = rs.getString("failure_message") ?: FailureReasons.of(code).message)
            },
            updatedAt = rs.instant("updated_at"),
        )

        fun attempt(rs: ResultSet) = Attempt(
            executionId = ExecutionId(rs.uuid("execution_id")),
            number = rs.getInt("number"),
            idempotencyKey = rs.getString("idempotency_key"),
            startedAt = rs.instant("started_at"),
            finishedAt = rs.instantOrNull("finished_at"),
            outcome = rs.getString("outcome")?.let(AttemptOutcome::valueOf),
            reason = rs.getString("reason_code")?.let { code ->
                FailureReasons.of(code).copy(message = rs.getString("reason_message") ?: FailureReasons.of(code).message)
            },
            transactionId = rs.uuidOrNull("transaction_id"),
            endToEndId = rs.getString("end_to_end_id"),
        )
    }
}

package br.com.walletotp.adapter.out.persistence

import br.com.walletotp.application.port.ChallengeRepository
import br.com.walletotp.domain.Challenge
import br.com.walletotp.domain.ChallengeId
import br.com.walletotp.domain.Channel
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.SendLimits
import br.com.walletotp.domain.Subject
import br.com.walletotp.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Every query filters by tenant_id: the tenant comes from the token, and no challenge crosses tenants. */
@Repository
class JdbcChallengeRepository(private val jdbc: JdbcClient) : ChallengeRepository {

    override fun lockForCreation(tenantId: TenantId, destinationHash: String, subject: Subject, purpose: Purpose) {
        // Transaction-scoped advisory locks on a hash of each key: released at commit or rollback.
        advisoryLock("destination:$tenantId:$destinationHash")
        advisoryLock("subject:$tenantId:${subject.value}:$purpose")
    }

    private fun advisoryLock(key: String) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key)
            .query { _, _ -> 1 }.list()
    }

    override fun recent(tenantId: TenantId, subject: Subject, purpose: Purpose, destinationHash: String,
                        since: Instant): SendLimits.Recent {
        val forSubject = jdbc.sql(
            """
            SELECT count(*) AS n, min(created_at) AS oldest, max(created_at) AS newest
              FROM challenge
             WHERE tenant_id = :tenant AND subject = :subject AND purpose = :purpose
               AND status <> 'FAILED' AND created_at > :since
            """.trimIndent(),
        ).param("tenant", tenantId.value).param("subject", subject.value).param("purpose", purpose.name)
            .param("since", ts(since))
            .query { rs, _ -> Triple(rs.getInt("n"), instant(rs, "oldest"), instant(rs, "newest")) }.single()
        val forDestination = jdbc.sql(
            """
            SELECT count(*) AS n, min(created_at) AS oldest
              FROM challenge
             WHERE tenant_id = :tenant AND destination_hash = :destination
               AND status <> 'FAILED' AND created_at > :since
            """.trimIndent(),
        ).param("tenant", tenantId.value).param("destination", destinationHash).param("since", ts(since))
            .query { rs, _ -> SendLimits.Window(rs.getInt("n"), instant(rs, "oldest")) }.single()
        return SendLimits.Recent(forSubject.third, SendLimits.Window(forSubject.first, forSubject.second), forDestination)
    }

    override fun supersedeOpen(tenantId: TenantId, subject: Subject, purpose: Purpose) {
        jdbc.sql(
            """
            UPDATE challenge SET status = 'SUPERSEDED'
             WHERE tenant_id = :tenant AND subject = :subject AND purpose = :purpose AND status = 'OPEN'
            """.trimIndent(),
        ).param("tenant", tenantId.value).param("subject", subject.value).param("purpose", purpose.name).update()
    }

    override fun insert(challenge: Challenge) {
        jdbc.sql(
            """
            INSERT INTO challenge (id, tenant_id, subject, purpose, channel, destination_masked, destination_hash,
                                   code_hash, context_hash, status, attempts, created_at, expires_at, used_at)
            VALUES (:id, :tenant, :subject, :purpose, :channel, :masked, :destination, :code, :context, :status,
                    :attempts, :createdAt, :expiresAt, :usedAt)
            """.trimIndent(),
        )
            .param("id", challenge.id.value)
            .param("tenant", challenge.tenantId.value)
            .param("subject", challenge.subject.value)
            .param("purpose", challenge.purpose.name)
            .param("channel", challenge.channel.name)
            .param("masked", challenge.destinationMasked)
            .param("destination", challenge.destinationHash)
            .param("code", challenge.codeHash)
            .param("context", challenge.contextHash, Types.VARCHAR)
            .param("status", challenge.status.name)
            .param("attempts", challenge.attempts)
            .param("createdAt", ts(challenge.createdAt))
            .param("expiresAt", ts(challenge.expiresAt))
            .param("usedAt", challenge.usedAt?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
            .update()
    }

    override fun lock(tenantId: TenantId, id: ChallengeId): Challenge? =
        jdbc.sql("SELECT * FROM challenge WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
            .param("tenant", tenantId.value).param("id", id.value)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    override fun update(challenge: Challenge) {
        jdbc.sql("UPDATE challenge SET status = :status, attempts = :attempts, used_at = :usedAt WHERE id = :id")
            .param("status", challenge.status.name)
            .param("attempts", challenge.attempts)
            .param("usedAt", challenge.usedAt?.let(::ts), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("id", challenge.id.value)
            .update()
    }

    private companion object {
        fun ts(i: Instant): OffsetDateTime = i.atOffset(ZoneOffset.UTC)

        fun instant(rs: ResultSet, column: String): Instant? = rs.getTimestamp(column)?.toInstant()

        fun map(rs: ResultSet) = Challenge(
            id = ChallengeId(rs.getObject("id", UUID::class.java)),
            tenantId = TenantId(rs.getObject("tenant_id", UUID::class.java)),
            subject = Subject.of(rs.getString("subject")),
            purpose = Purpose.valueOf(rs.getString("purpose")),
            channel = Channel.valueOf(rs.getString("channel")),
            destinationMasked = rs.getString("destination_masked"),
            destinationHash = rs.getString("destination_hash"),
            codeHash = rs.getString("code_hash"),
            contextHash = rs.getString("context_hash"),
            status = Challenge.Status.valueOf(rs.getString("status")),
            attempts = rs.getInt("attempts"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            usedAt = instant(rs, "used_at"),
        )
    }
}

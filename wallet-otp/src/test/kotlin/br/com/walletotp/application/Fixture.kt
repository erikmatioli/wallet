package br.com.walletotp.application

import br.com.walletotp.application.port.ChallengeRepository
import br.com.walletotp.application.port.CodeGenerator
import br.com.walletotp.application.port.CodeSender
import br.com.walletotp.application.port.OtpMetrics
import br.com.walletotp.application.port.Transactions
import br.com.walletotp.domain.Challenge
import br.com.walletotp.domain.ChallengeId
import br.com.walletotp.domain.Email
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.SendLimits
import br.com.walletotp.domain.Subject
import br.com.walletotp.domain.TenantId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test moves by hand. */
class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

class InMemoryChallenges : ChallengeRepository {
    val rows = linkedMapOf<ChallengeId, Challenge>()

    override fun lockForCreation(tenantId: TenantId, destinationHash: String, subject: Subject, purpose: Purpose) {}

    override fun recent(tenantId: TenantId, subject: Subject, purpose: Purpose, destinationHash: String,
                        since: Instant): SendLimits.Recent {
        val counted = rows.values.filter { it.tenantId == tenantId && it.status != Challenge.Status.FAILED && it.createdAt.isAfter(since) }
        val forSubject = counted.filter { it.subject == subject && it.purpose == purpose }
        val forDestination = counted.filter { it.destinationHash == destinationHash }
        return SendLimits.Recent(
            forSubject.maxOfOrNull { it.createdAt },
            SendLimits.Window(forSubject.size, forSubject.minOfOrNull { it.createdAt }),
            SendLimits.Window(forDestination.size, forDestination.minOfOrNull { it.createdAt }),
        )
    }

    override fun supersedeOpen(tenantId: TenantId, subject: Subject, purpose: Purpose) {
        rows.replaceAll { _, c -> if (c.tenantId == tenantId && c.subject == subject && c.purpose == purpose) c.superseded() else c }
    }

    override fun insert(challenge: Challenge) {
        rows[challenge.id] = challenge
    }

    override fun lock(tenantId: TenantId, id: ChallengeId) = rows[id]?.takeIf { it.tenantId == tenantId }

    override fun update(challenge: Challenge) {
        rows[challenge.id] = challenge
    }
}

/** Keeps what was "sent", so a test can type the code back; can be told to fail like a down SMTP server. */
class RecordingSender : CodeSender {
    data class Sent(val destination: String, val code: String, val purpose: Purpose)

    val sent = mutableListOf<Sent>()
    var failing = false

    override fun send(destination: Email, code: String, purpose: Purpose, expiresAt: Instant) {
        if (failing) throw IllegalStateException("SMTP down")
        sent += Sent(destination.value, code, purpose)
    }

    fun lastCode() = sent.last().code
}

class Fixture {
    val clock = MutableClock(Instant.parse("2026-10-09T13:00:00Z"))
    val challenges = InMemoryChallenges()
    val sender = RecordingSender()
    val metrics = mutableListOf<String>()
    private var next = 0

    /** Codes 100001, 100002...: different every time, and known to the test. */
    private val codes = CodeGenerator { (100_001 + next++).toString() }
    private val tx = object : Transactions {
        override fun <T> inTransaction(work: () -> T): T = work()
    }
    private val recorder = object : OtpMetrics {
        override fun challenge(purpose: Purpose, result: String) {
            metrics += "challenge:$purpose:$result"
        }

        override fun verification(purpose: Purpose, result: String) {
            metrics += "verification:$purpose:$result"
        }
    }

    val secrets = Secrets("test-key-0123456789abcdef0123456789".toByteArray())
    val service = ChallengeService(challenges, sender, codes, secrets, SendLimits(), tx, recorder, clock)
    val tenant = TenantId(UUID.randomUUID())

    fun create(subject: String = "52998224725", purpose: Purpose = Purpose.LOGIN, email: String = "maria@example.com",
               context: String? = null, tenantId: TenantId = tenant) =
        service.create(ChallengeService.Create(tenantId, subject, purpose, br.com.walletotp.domain.Channel.EMAIL, email, context))

    fun verify(id: ChallengeId, code: String, subject: String = "52998224725", context: String? = null,
               tenantId: TenantId = tenant) =
        service.verify(ChallengeService.Verify(tenantId, id.toString(), subject, code, context))

    fun stored(id: ChallengeId) = challenges.rows.getValue(id)

    fun later(seconds: Long) {
        clock.now = clock.now.plusSeconds(seconds)
    }
}

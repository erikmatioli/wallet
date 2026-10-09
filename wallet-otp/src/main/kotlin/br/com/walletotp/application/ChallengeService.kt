package br.com.walletotp.application

import br.com.walletotp.application.port.ChallengeRepository
import br.com.walletotp.application.port.CodeGenerator
import br.com.walletotp.application.port.CodeSender
import br.com.walletotp.application.port.OtpMetrics
import br.com.walletotp.application.port.Transactions
import br.com.walletotp.domain.BusinessRuleException
import br.com.walletotp.domain.Challenge
import br.com.walletotp.domain.ChallengeId
import br.com.walletotp.domain.Channel
import br.com.walletotp.domain.Email
import br.com.walletotp.domain.NotFoundException
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.SendLimits
import br.com.walletotp.domain.Subject
import br.com.walletotp.domain.TenantId
import java.time.Clock
import java.time.Instant

/** Sending a code and checking it (ADR-001). The caller decides who the subject is; this only proves possession. */
class ChallengeService(
    private val challenges: ChallengeRepository,
    private val sender: CodeSender,
    private val codes: CodeGenerator,
    private val secrets: Secrets,
    private val limits: SendLimits,
    private val tx: Transactions,
    private val metrics: OtpMetrics,
    private val clock: Clock,
) {

    data class Create(val tenantId: TenantId, val subject: String?, val purpose: Purpose, val channel: Channel,
                      val destination: String?, val context: String?)

    data class Created(val id: ChallengeId, val expiresAt: Instant, val destinationMasked: String)

    data class Verify(val tenantId: TenantId, val id: String, val subject: String?, val code: String?,
                      val context: String?)

    /**
     * Records the challenge, then sends the code. Recording first means the limits and the "only the newest
     * code is valid" rule hold even if two requests arrive at once (they are serialized by the lock). The
     * email goes out after the commit: a database never waits on an SMTP server.
     *
     * @throws br.com.walletotp.domain.TooManyRequestsException when a send limit is reached
     * @throws CodeNotSentException when the email could not be sent (the challenge is then FAILED)
     */
    fun create(c: Create): Created {
        val subject = Subject.of(c.subject)
        val destination = Email.of(c.destination)
        val destinationHash = secrets.destination(destination)
        val id = ChallengeId.new()
        val code = codes.next()
        val challenge = tx.inTransaction {
            val now = clock.instant()
            challenges.lockForCreation(c.tenantId, destinationHash, subject, c.purpose)
            try {
                limits.check(challenges.recent(c.tenantId, subject, c.purpose, destinationHash, now.minus(limits.window)), now)
            } catch (e: RuntimeException) {
                metrics.challenge(c.purpose, "limited")
                throw e
            }
            challenges.supersedeOpen(c.tenantId, subject, c.purpose)
            Challenge.open(id, c.tenantId, subject, c.purpose, c.channel, destination, destinationHash,
                secrets.code(id, code), context(c.context), now).also(challenges::insert)
        }
        try {
            sender.send(destination, code, c.purpose, challenge.expiresAt)
        } catch (e: RuntimeException) {
            tx.inTransaction { challenges.update(challenge.failed()) }
            metrics.challenge(c.purpose, "send_failed")
            throw CodeNotSentException("the code could not be sent: ${e.message}", e)
        }
        metrics.challenge(c.purpose, "sent")
        return Created(challenge.id, challenge.expiresAt, challenge.destinationMasked)
    }

    /**
     * Checks a code once. An unknown id, another subject and another context all answer the same
     * CHALLENGE_NOT_FOUND (decision 3): the caller learns nothing about which of them was wrong, and none of
     * them counts as an attempt - only a wrong code for the right challenge does.
     */
    fun verify(v: Verify) {
        val id = ChallengeId.parse(v.id) ?: throw notFound()
        val subject = Subject.of(v.subject)
        val code = v.code.orEmpty().trim()
        val (purpose, outcome) = tx.inTransaction {
            val challenge = challenges.lock(v.tenantId, id)
                ?.takeIf { it.subject == subject && sameContext(it.contextHash, v.context) }
                ?: throw notFound()
            val (after, outcome) = challenge.verify(secrets.same(secrets.code(id, code), challenge.codeHash), clock.instant())
            if (after != challenge) challenges.update(after)
            challenge.purpose to outcome
        }
        metrics.verification(purpose, name(outcome))
        when (outcome) {
            Challenge.Outcome.Verified -> return
            is Challenge.Outcome.WrongCode -> throw BusinessRuleException("INVALID_CODE",
                "wrong code, ${outcome.attemptsLeft} attempt(s) left", outcome.attemptsLeft)
            Challenge.Outcome.Locked -> throw BusinessRuleException("CHALLENGE_LOCKED", "too many wrong codes, ask for a new one", 0)
            is Challenge.Outcome.Closed -> throw when (outcome.reason) {
                null -> BusinessRuleException("CHALLENGE_EXPIRED", "the code expired, ask for a new one")
                Challenge.Status.USED -> BusinessRuleException("CHALLENGE_ALREADY_USED", "this code was already used")
                Challenge.Status.LOCKED -> BusinessRuleException("CHALLENGE_LOCKED", "too many wrong codes, ask for a new one", 0)
                Challenge.Status.SUPERSEDED -> BusinessRuleException("CHALLENGE_SUPERSEDED", "a newer code was sent, use that one")
                // The customer never received it; for them it does not exist.
                Challenge.Status.FAILED, Challenge.Status.OPEN -> notFound()
            }
        }
    }

    private fun context(raw: String?): String? = raw?.takeIf { it.isNotEmpty() }?.let(secrets::context)

    private fun sameContext(stored: String?, given: String?): Boolean {
        val hashed = context(given)
        return if (stored == null || hashed == null) stored == hashed else secrets.same(stored, hashed)
    }

    private fun notFound() = NotFoundException("CHALLENGE_NOT_FOUND", "challenge not found")

    private fun name(o: Challenge.Outcome) = when (o) {
        Challenge.Outcome.Verified -> "verified"
        is Challenge.Outcome.WrongCode -> "wrong_code"
        Challenge.Outcome.Locked -> "locked"
        is Challenge.Outcome.Closed -> (o.reason?.name ?: "EXPIRED").lowercase()
    }
}

/** The SMTP server (or whatever sends) did not take the message: 503, the customer asks again. */
class CodeNotSentException(message: String, cause: Throwable) : RuntimeException(message, cause)

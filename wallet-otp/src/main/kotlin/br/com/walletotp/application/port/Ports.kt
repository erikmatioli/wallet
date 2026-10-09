package br.com.walletotp.application.port

import br.com.walletotp.domain.Challenge
import br.com.walletotp.domain.ChallengeId
import br.com.walletotp.domain.Email
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.SendLimits
import br.com.walletotp.domain.Subject
import br.com.walletotp.domain.TenantId
import java.time.Instant

interface ChallengeRepository {

    /**
     * Serializes, until the end of the transaction, every creation for this subject and purpose and every
     * creation to this destination - so two requests at once cannot both pass the send limits. Always
     * destination first, then subject: one order for everyone, so no two requests wait on each other.
     */
    fun lockForCreation(tenantId: TenantId, destinationHash: String, subject: Subject, purpose: Purpose)

    /** The codes that count for the limits since [since] (FAILED ones do not). */
    fun recent(tenantId: TenantId, subject: Subject, purpose: Purpose, destinationHash: String, since: Instant): SendLimits.Recent

    /** OPEN challenges of this subject and purpose become SUPERSEDED: only the newest code is valid. */
    fun supersedeOpen(tenantId: TenantId, subject: Subject, purpose: Purpose)

    fun insert(challenge: Challenge)

    /** Locks the challenge until the end of the transaction, so two verifications of it are serialized. */
    fun lock(tenantId: TenantId, id: ChallengeId): Challenge?

    fun update(challenge: Challenge)
}

/** Delivers a code (ADR-001, decision 6). Throws when it could not be handed to the provider. */
interface CodeSender {
    fun send(destination: Email, code: String, purpose: Purpose, expiresAt: Instant)
}

/** The 6 digits. A port so tests can know the code that was "sent". */
fun interface CodeGenerator {
    fun next(): String
}

interface Transactions {
    fun <T> inTransaction(work: () -> T): T
}

/** Business metrics: what happened, in domain terms; the adapter decides how it becomes a metric. */
interface OtpMetrics {
    fun challenge(purpose: Purpose, result: String)

    fun verification(purpose: Purpose, result: String)
}

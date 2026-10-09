package br.com.walletotp.domain

import java.time.Duration
import java.time.Instant

/**
 * A code sent to someone, waiting to be typed back (ADR-001, decisions 2 and 4).
 *
 * ```
 * OPEN --right code--> USED
 *   |  --5th wrong code--> LOCKED
 *   |  --a newer code for the same subject and purpose--> SUPERSEDED
 *   \  --the email could not be sent--> FAILED
 * ```
 * An OPEN challenge past [expiresAt] is expired: there is no job to mark it, the clock decides.
 *
 * @property codeHash        HMAC of the code - the code itself is never stored
 * @property contextHash     HMAC of what is being approved, when the caller gave one
 * @property destinationHash HMAC of the email, only to count codes per destination (decision 5)
 */
data class Challenge(
    val id: ChallengeId,
    val tenantId: TenantId,
    val subject: Subject,
    val purpose: Purpose,
    val channel: Channel,
    val destinationMasked: String,
    val destinationHash: String,
    val codeHash: String,
    val contextHash: String?,
    val status: Status,
    val attempts: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
) {
    enum class Status { OPEN, USED, LOCKED, SUPERSEDED, FAILED }

    /** What a verification answered, and the challenge as it is afterwards. */
    sealed interface Outcome {
        data object Verified : Outcome

        /** Wrong code; [attemptsLeft] before the challenge locks. */
        data class WrongCode(val attemptsLeft: Int) : Outcome

        /** Wrong code and no attempt left: the challenge is now LOCKED. */
        data object Locked : Outcome

        /** Nothing was counted: the challenge could not be verified anymore. */
        data class Closed(val reason: Status?) : Outcome
    }

    fun expired(now: Instant) = !now.isBefore(expiresAt)

    /**
     * Checks a code typed by the customer. [matches] is the constant-time comparison of its hash with
     * [codeHash], done by the caller that holds the key. A wrong code counts; anything else does not.
     */
    fun verify(matches: Boolean, now: Instant): Pair<Challenge, Outcome> {
        if (status != Status.OPEN) return this to Outcome.Closed(status)
        if (expired(now)) return this to Outcome.Closed(null)
        if (matches) return copy(status = Status.USED, usedAt = now) to Outcome.Verified
        val tried = attempts + 1
        return if (tried >= MAX_ATTEMPTS) {
            copy(status = Status.LOCKED, attempts = tried) to Outcome.Locked
        } else {
            copy(attempts = tried) to Outcome.WrongCode(MAX_ATTEMPTS - tried)
        }
    }

    fun superseded(): Challenge = if (status == Status.OPEN) copy(status = Status.SUPERSEDED) else this

    fun failed(): Challenge = copy(status = Status.FAILED)

    companion object {
        const val MAX_ATTEMPTS = 5
        val VALIDITY: Duration = Duration.ofMinutes(5)

        fun open(id: ChallengeId, tenantId: TenantId, subject: Subject, purpose: Purpose, channel: Channel,
                 destination: Email, destinationHash: String, codeHash: String, contextHash: String?,
                 now: Instant): Challenge =
            Challenge(id, tenantId, subject, purpose, channel, destination.masked, destinationHash, codeHash,
                contextHash, Status.OPEN, 0, now, now.plus(VALIDITY), null)
    }
}

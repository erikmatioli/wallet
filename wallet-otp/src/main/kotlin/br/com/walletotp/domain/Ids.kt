package br.com.walletotp.domain

import java.util.UUID

// Value classes: a TenantId and a ChallengeId are both UUIDs, but the compiler keeps them apart.

@JvmInline
value class TenantId(val value: UUID) {
    override fun toString() = value.toString()
}

@JvmInline
value class ChallengeId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = ChallengeId(UUID.randomUUID())

        /** An id from outside (a path variable): an invalid one is simply a challenge that does not exist. */
        fun parse(raw: String): ChallengeId? = runCatching { ChallengeId(UUID.fromString(raw)) }.getOrNull()
    }
}

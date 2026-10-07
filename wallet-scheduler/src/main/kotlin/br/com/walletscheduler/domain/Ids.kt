package br.com.walletscheduler.domain

import java.util.UUID

// Value classes: a TenantId and an AccountId are both UUIDs, but the compiler keeps them apart, and
// at runtime they are just the UUID (no wrapper object) - Kotlin's take on the record-per-id of wallet-core.

@JvmInline
value class TenantId(val value: UUID) {
    override fun toString() = value.toString()
}

@JvmInline
value class AccountId(val value: UUID) {
    override fun toString() = value.toString()
}

@JvmInline
value class ScheduleId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = ScheduleId(UUID.randomUUID())
    }
}

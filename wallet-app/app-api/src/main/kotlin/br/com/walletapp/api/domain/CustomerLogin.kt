package br.com.walletapp.api.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

@JvmInline
value class LoginId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = LoginId(UUID.randomUUID())
    }
}

@JvmInline
value class AccountId(val value: UUID) {
    override fun toString() = value.toString()
}

/** The password as typed: checked against the policy, then only its hash is kept. Never logged. */
@JvmInline
value class Password private constructor(val value: String) {
    override fun toString() = "********"

    companion object {
        private const val MIN = 8
        private const val MAX = 72 // BCrypt ignores anything past 72 bytes: refuse rather than silently cut

        fun of(raw: String): Password {
            if (raw.length !in MIN..MAX || raw.none(Char::isLetter) || raw.none(Char::isDigit)) {
                throw ValidationException("WEAK_PASSWORD",
                    "A senha precisa ter de $MIN a $MAX caracteres, com letras e números.")
            }
            return Password(raw)
        }

        /**
         * The first-access password of a customer wallet-core already had before the app (opened by the
         * console): their CPF, digits only. Outside the policy on purpose - it has no letters - and only
         * ever compared against what the customer typed, never offered as a choice.
         */
        fun firstAccess(cpf: Cpf) = Password(cpf.digits)
    }
}

enum class LoginStatus { PENDING, ACTIVE }

/**
 * The customer as the app knows them (ADR-001, decision 4): CPF and password hash, and the wallet-core
 * account the login gives access to.
 *
 * PENDING while the signup has not finished in wallet-core: if app-api stops between creating the
 * customer there and activating here, the next signup with the same CPF finds this row and finishes the
 * job instead of being refused as "customer created outside the app".
 */
data class CustomerLogin(
    val id: LoginId,
    val cpf: Cpf,
    val name: String,
    val passwordHash: String,
    val status: LoginStatus,
    val accountId: AccountId?,
    val failedAttempts: Int,
    val lockedUntil: Instant?,
    val createdAt: Instant,
) {
    fun locked(now: Instant) = lockedUntil?.isAfter(now) == true

    /** The fifth wrong password in a row locks the login for 15 minutes, and the count starts again. */
    fun failedLogin(now: Instant): CustomerLogin =
        if (failedAttempts + 1 >= MAX_ATTEMPTS) copy(failedAttempts = 0, lockedUntil = now.plus(LOCK))
        else copy(failedAttempts = failedAttempts + 1)

    fun succeededLogin(): CustomerLogin = copy(failedAttempts = 0, lockedUntil = null)

    fun activated(accountId: AccountId): CustomerLogin = copy(status = LoginStatus.ACTIVE, accountId = accountId)

    companion object {
        const val MAX_ATTEMPTS = 5
        val LOCK: Duration = Duration.ofMinutes(15)

        fun pending(cpf: Cpf, name: String, passwordHash: String, now: Instant): CustomerLogin {
            val n = name.trim().replace(Regex("\\s+"), " ")
            if (n.length !in 3..140) throw ValidationException("INVALID_NAME", "Informe o nome completo.")
            return CustomerLogin(LoginId.new(), cpf, n, passwordHash, LoginStatus.PENDING, null, 0, null, now)
        }
    }
}

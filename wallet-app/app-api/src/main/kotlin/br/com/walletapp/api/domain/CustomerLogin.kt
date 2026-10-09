package br.com.walletapp.api.domain

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

/** The customer's email, where the codes go (ADR-002). Never logged in full: [toString] masks it. */
@JvmInline
value class Email private constructor(val value: String) {

    /** "m***@example.com": the customer recognizes it, nobody else can write to it. */
    val masked: String get() = value.first() + "***" + value.substring(value.indexOf('@'))

    override fun toString() = masked

    companion object {
        private val FORMAT = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

        fun of(raw: String?): Email {
            val v = raw.orEmpty().trim().lowercase()
            if (v.length > 254 || !FORMAT.matches(v)) throw ValidationException("INVALID_EMAIL", "Informe um e-mail válido.")
            return Email(v)
        }
    }
}

enum class LoginStatus { PENDING, ACTIVE }

/**
 * The customer as the app knows them (ADR-002): CPF, the email the codes go to, and the wallet-core account
 * the login gives access to. There is no password: every login is a code sent to [email].
 *
 * PENDING while the signup has not finished in wallet-core: if app-api stops between creating the
 * customer there and activating here, the next signup with the same CPF finds this row and finishes the
 * job instead of being refused as "customer created outside the app".
 *
 * [email] is null only on logins made before ADR-002 (with a password): they cannot log in any more.
 */
data class CustomerLogin(
    val id: LoginId,
    val cpf: Cpf,
    val name: String,
    val email: Email?,
    val status: LoginStatus,
    val accountId: AccountId?,
    val createdAt: Instant,
) {
    fun activated(accountId: AccountId): CustomerLogin = copy(status = LoginStatus.ACTIVE, accountId = accountId)

    /** Can receive a login code: an active login with an email. */
    val canLogIn: Boolean get() = status == LoginStatus.ACTIVE && email != null

    companion object {
        fun pending(cpf: Cpf, name: String, email: Email, now: Instant): CustomerLogin =
            CustomerLogin(LoginId.new(), cpf, normalizeName(name), email, LoginStatus.PENDING, null, now)

        fun normalizeName(name: String): String {
            val n = name.trim().replace(Regex("""\s+"""), " ")
            if (n.length !in 3..140) throw ValidationException("INVALID_NAME", "Informe o nome completo.")
            return n
        }
    }
}

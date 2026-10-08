package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.Onboarding
import br.com.walletapp.api.application.port.PasswordHasher
import br.com.walletapp.api.application.port.SessionTokens
import br.com.walletapp.api.domain.AuthenticationException
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.ConflictException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.LoginStatus
import br.com.walletapp.api.domain.Password
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.Session
import java.time.Clock
import java.util.UUID

/** Opening an account and logging in (ADR-001, decision 4). */
class AuthService(
    private val core: CoreBanking,
    private val logins: LoginRepository,
    private val hasher: PasswordHasher,
    private val tokens: SessionTokens,
    private val clock: Clock,
) {

    /**
     * Creates the login as PENDING, then the customer and account in wallet-core, then activates the
     * login - and logs the customer in. A PENDING login found here is a signup that stopped halfway: it
     * is finished, not refused.
     */
    fun signup(rawCpf: String, name: String, rawPassword: String): Session {
        val cpf = Cpf.parse(rawCpf)
        val hash = hasher.hash(Password.of(rawPassword))
        val existing = logins.find(cpf)
        val login = when (existing?.status) {
            LoginStatus.ACTIVE -> throw ConflictException("CPF_ALREADY_REGISTERED", "Este CPF já tem conta no app. Faça login.")
            // A signup that stopped halfway: finish it, with the name and password typed now.
            LoginStatus.PENDING -> CustomerLogin.pending(cpf, name, hash, clock.instant())
                .copy(id = existing.id, createdAt = existing.createdAt).also(logins::update)
            null -> CustomerLogin.pending(cpf, name, hash, clock.instant()).also {
                if (!logins.insert(it)) {
                    throw ConflictException("CPF_ALREADY_REGISTERED", "Este CPF já tem conta no app. Faça login.")
                }
            }
        }

        val accountId = when (val result = core.onboard(login.name, cpf, "app:${login.id}")) {
            is Onboarding.Created -> result.accountId
            Onboarding.AlreadyExists -> if (existing != null) {
                // Our earlier attempt created it in wallet-core and stopped before activating here.
                core.accountByCpf(cpf) ?: error("wallet-core has the customer ${cpf.masked} but no account")
            } else {
                // Created outside the app (e.g. by the console): not linked without proof of identity.
                logins.delete(login.id)
                throw BusinessRuleException("CUSTOMER_EXISTS_OUTSIDE_APP",
                    "Já existe um cadastro com este CPF. Entre pelo login usando o seu CPF, só números, como senha.")
            }
        }
        val active = login.activated(accountId)
        logins.update(active)
        return session(active)
    }

    /**
     * The same message for an unknown CPF and a wrong password, so the login does not tell which CPFs
     * have an account. The fifth wrong password in a row locks the login for 15 minutes.
     */
    fun login(rawCpf: String, rawPassword: String): Session {
        val cpf = try {
            Cpf.parse(rawCpf)
        } catch (e: ValidationException) {
            throw invalidCredentials()
        }
        val login = logins.find(cpf)?.takeIf { it.status == LoginStatus.ACTIVE }
            ?: firstAccess(cpf, rawPassword)
        if (login == null) {
            hasher.matches(rawPassword, dummyHash) // same time as a real check: no timing hint either
            throw invalidCredentials()
        }
        val now = clock.instant()
        if (login.locked(now)) {
            throw BusinessRuleException("LOGIN_LOCKED", "Muitas tentativas erradas. Tente de novo em alguns minutos.")
        }
        if (!hasher.matches(rawPassword, login.passwordHash)) {
            logins.update(login.failedLogin(now))
            throw invalidCredentials()
        }
        val ok = login.succeededLogin()
        if (ok != login) logins.update(ok)
        return session(ok)
    }

    /**
     * A customer wallet-core had before the app (opened by the console) has no login here. Their first
     * login uses the CPF, digits only, as the password, and creates the login, linked to the account
     * wallet-core already has. wallet-core is only asked when that is the password typed, so a wrong
     * password for an unknown CPF costs no call. A PENDING login is a signup of the app, not this case.
     */
    private fun firstAccess(cpf: Cpf, rawPassword: String): CustomerLogin? {
        if (rawPassword != cpf.digits || logins.find(cpf) != null) return null
        val accountId = core.accountByCpf(cpf) ?: return null
        val name = core.me(accountId).customerName.ifBlank { "Cliente" }
        val login = CustomerLogin.pending(cpf, name, hasher.hash(Password.firstAccess(cpf)), clock.instant())
            .activated(accountId)
        // A concurrent first login inserted it first: use theirs, the password check below is the same.
        return if (logins.insert(login)) login else logins.find(cpf)?.takeIf { it.status == LoginStatus.ACTIVE }
    }

    private fun session(login: CustomerLogin): Session {
        val issued = tokens.issue(login)
        return Session(issued.token, issued.expiresInSeconds, login.name)
    }

    private fun invalidCredentials() = AuthenticationException("INVALID_CREDENTIALS", "CPF ou senha inválidos.")

    /** A real hash of a password nobody knows, made once: checked against when the CPF has no login. */
    private val dummyHash: String by lazy { hasher.hash(Password.of("x${UUID.randomUUID()}9")) }
}

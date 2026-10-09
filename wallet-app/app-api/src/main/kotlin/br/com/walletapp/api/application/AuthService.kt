package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.Onboarding
import br.com.walletapp.api.application.port.OtpCheck
import br.com.walletapp.api.application.port.OtpGateway
import br.com.walletapp.api.application.port.OtpPurpose
import br.com.walletapp.api.application.port.OtpSend
import br.com.walletapp.api.application.port.SessionTokens
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.AuthenticationException
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.ConflictException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.DomainException
import br.com.walletapp.api.domain.Email
import br.com.walletapp.api.domain.LoginStatus
import br.com.walletapp.api.domain.TooManyRequestsException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.CodeSent
import br.com.walletapp.contract.Session
import java.time.Clock
import java.util.UUID

/**
 * Opening an account and logging in with a code sent to the customer's email (ADR-002). There is no
 * password: wallet-otp sends and checks the codes, this decides who the customer is - and which email is
 * theirs: the one confirmed at the app's signup, or the one the operator registered in wallet-core (ADR-003).
 */
class AuthService(
    private val core: CoreBanking,
    private val logins: LoginRepository,
    private val otp: OtpGateway,
    private val tokens: SessionTokens,
    private val throttle: StartThrottle,
    private val clock: Clock,
) {

    /**
     * Sends the code that proves the customer owns [rawEmail]. Nothing is created yet, and nothing tells
     * whether the CPF already has an account: that is only answered after the code (decision 2).
     */
    fun startSignup(rawCpf: String, name: String, rawEmail: String): CodeSent {
        val cpf = Cpf.parse(rawCpf)
        CustomerLogin.normalizeName(name)
        val email = Email.of(rawEmail)
        return when (val sent = otp.send(cpf, OtpPurpose.SIGNUP, email, signupContext(email))) {
            is OtpSend.Sent -> CodeSent(sent.challengeId, "Enviamos um código para ${sent.emailMasked}. Ele vale por 5 minutos.")
            is OtpSend.TooSoon -> throw TooManyRequestsException(StartThrottle.waitMessage(sent.retryAfterSeconds), sent.retryAfterSeconds)
        }
    }

    /**
     * Checks the code - bound to the email it was sent to, so it confirms no other - then opens the customer
     * and account in wallet-core and activates the login. A PENDING login found here is a signup that stopped
     * halfway: it is finished, not refused.
     */
    fun confirmSignup(challengeId: String, code: String, rawCpf: String, name: String, rawEmail: String): Session {
        val cpf = Cpf.parse(rawCpf)
        val email = Email.of(rawEmail)
        checkCode(otp.verify(challengeId, cpf, code, signupContext(email)))

        val existing = logins.find(cpf)
        val login = when (existing?.status) {
            LoginStatus.ACTIVE -> throw ConflictException("CPF_ALREADY_REGISTERED", "Este CPF já tem conta no app. Entre pelo login.")
            LoginStatus.PENDING -> CustomerLogin.pending(cpf, name, email, clock.instant())
                .copy(id = existing.id, createdAt = existing.createdAt).also(logins::update)
            null -> CustomerLogin.pending(cpf, name, email, clock.instant()).also {
                if (!logins.insert(it)) {
                    throw ConflictException("CPF_ALREADY_REGISTERED", "Este CPF já tem conta no app. Entre pelo login.")
                }
            }
        }

        val accountId = when (val result = core.onboard(login.name, cpf, "app:${login.id}", email)) {
            is Onboarding.Created -> result.accountId
            Onboarding.AlreadyExists -> if (existing != null) {
                // Our earlier attempt created it in wallet-core and stopped before activating here.
                core.accountByCpf(cpf) ?: error("wallet-core has the customer ${cpf.masked} but no account")
            } else {
                // Opened by the console. The email typed here proves nothing about who the customer is, so it
                // is never linked from a signup (ADR-003): the customer logs in, and the code goes to the
                // email the operator registered.
                logins.delete(login.id)
                throw if (core.contact(cpf)?.email != null) {
                    BusinessRuleException("CUSTOMER_EXISTS_USE_LOGIN",
                        "Este CPF já tem conta. Use Entrar: o código vai para o e-mail cadastrado no atendimento.")
                } else {
                    BusinessRuleException("CUSTOMER_EXISTS_OUTSIDE_APP",
                        "Já existe um cadastro com este CPF. Para usar o app, procure o atendimento e cadastre seu e-mail.")
                }
            }
        }
        val active = login.activated(accountId)
        logins.update(active)
        return session(active)
    }

    /**
     * Sends a login code to the customer's email: the app login's, or - for a customer the operator opened in
     * the console, with no app login yet - the one registered in wallet-core (ADR-003). The answer is the
     * same in every case (ADR-002, decision 3): the throttle applies to every CPF, and a CPF with nowhere to
     * send gets a made-up challenge id that never verifies.
     */
    fun startLogin(rawCpf: String): CodeSent {
        val cpf = Cpf.parse(rawCpf)
        throttle.check(cpf)
        val email = logins.find(cpf)?.takeIf { it.canLogIn }?.email ?: consoleCustomer(cpf)?.email
        val challengeId = if (email == null) {
            UUID.randomUUID().toString()
        } else {
            when (val sent = otp.send(cpf, OtpPurpose.LOGIN, email, null)) {
                is OtpSend.Sent -> sent.challengeId
                // wallet-otp's own limit (another instance, a restart): answered like the throttle's.
                is OtpSend.TooSoon -> throw TooManyRequestsException(StartThrottle.waitMessage(sent.retryAfterSeconds), sent.retryAfterSeconds)
            }
        }
        return CodeSent(challengeId, LOGIN_SENT)
    }

    /**
     * The code is the password: checked, it opens a session. For a customer opened in the console, the first
     * checked code also creates the app login, already active, linked to the account wallet-core has and
     * with the email the code went to (ADR-003).
     */
    fun confirmLogin(challengeId: String, rawCpf: String, code: String): Session {
        val cpf = try {
            Cpf.parse(rawCpf)
        } catch (e: ValidationException) {
            throw wrongCode()
        }
        val existing = logins.find(cpf)
        if (existing != null && existing.canLogIn) {
            checkCode(otp.verify(challengeId, cpf, code, null))
            return session(existing)
        }
        val customer = consoleCustomer(cpf) ?: throw wrongCode()
        checkCode(otp.verify(challengeId, cpf, code, null))
        return session(link(cpf, customer, existing))
    }

    /** A wallet-core customer the app can send a code to: with an email and a payment account. */
    private fun consoleCustomer(cpf: Cpf): ConsoleCustomer? {
        val contact = core.contact(cpf) ?: return null
        return ConsoleCustomer(contact.name, contact.email ?: return null, contact.accountId ?: return null)
    }

    private data class ConsoleCustomer(val name: String, val email: Email, val accountId: AccountId)

    /**
     * The app login of a console customer, created once the code proved they read the operator's email. A
     * row left by a signup that stopped halfway (PENDING) or made before ADR-002 (no email) is reused.
     */
    private fun link(cpf: Cpf, customer: ConsoleCustomer, existing: CustomerLogin?): CustomerLogin {
        val name = runCatching { CustomerLogin.normalizeName(customer.name) }.getOrDefault("Cliente")
        val active = if (existing != null) {
            existing.copy(name = name, email = customer.email).activated(customer.accountId).also(logins::update)
        } else {
            val created = CustomerLogin.pending(cpf, name, customer.email, clock.instant()).activated(customer.accountId)
            if (logins.insert(created)) created
            else logins.find(cpf)?.takeIf { it.canLogIn } ?: throw wrongCode() // a concurrent link won: use it
        }
        return active
    }

    private fun checkCode(check: OtpCheck) {
        if (check is OtpCheck.Refused) throw refusal(check.code)
    }

    /**
     * wallet-otp's answers in the customer's words. A code that does not exist and a wrong code read the
     * same: the customer only needs to know the code did not work.
     */
    private fun refusal(code: String): DomainException = when (code) {
        "INVALID_CODE", "CHALLENGE_NOT_FOUND" -> wrongCode()
        "CHALLENGE_EXPIRED", "CHALLENGE_SUPERSEDED", "CHALLENGE_ALREADY_USED" ->
            BusinessRuleException("CODE_EXPIRED", "Este código não vale mais. Peça um novo.")
        "CHALLENGE_LOCKED" -> BusinessRuleException("CODE_LOCKED", "Muitas tentativas erradas. Peça um novo código.")
        else -> BusinessRuleException("CODE_NOT_VERIFIED", "Não foi possível conferir o código. Peça um novo.")
    }

    private fun wrongCode() = AuthenticationException("INVALID_CODE", "Código incorreto. Confira o e-mail e tente de novo.")

    /** The signup code confirms this email and no other. */
    private fun signupContext(email: Email) = "signup-email:${email.value}"

    private fun session(login: CustomerLogin): Session {
        val issued = tokens.issue(login)
        return Session(issued.token, issued.expiresInSeconds, login.name)
    }

    private companion object {
        const val LOGIN_SENT = "Se houver conta para este CPF, enviamos um código para o e-mail cadastrado. Ele vale por 5 minutos."
    }
}

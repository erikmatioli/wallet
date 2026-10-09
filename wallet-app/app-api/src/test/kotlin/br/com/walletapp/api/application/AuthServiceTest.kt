package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.CoreContact
import br.com.walletapp.api.application.port.IssuedToken
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.Onboarding
import br.com.walletapp.api.application.port.OtpCheck
import br.com.walletapp.api.application.port.OtpGateway
import br.com.walletapp.api.application.port.OtpPurpose
import br.com.walletapp.api.application.port.OtpSend
import br.com.walletapp.api.application.port.PaymentOutcome
import br.com.walletapp.api.application.port.SessionTokens
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.AuthenticationException
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.ConflictException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.DomainException
import br.com.walletapp.api.domain.Email
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.LoginStatus
import br.com.walletapp.api.domain.TooManyRequestsException
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.Session
import br.com.walletapp.contract.StatementPage
import br.com.walletapp.contract.TransferDestination
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** wallet-core as the tests need it: which CPFs it already has, and a switch to "crash" after creating. */
class FakeCore : CoreBanking {
    val customers = mutableMapOf<String, AccountId>()
    var onboardCalls = 0

    /** The email wallet-core has for each CPF: set by onboard, or by the operator (console). */
    val emails = mutableMapOf<String, String>()

    /** Simulates app-api dying right after wallet-core created the customer. */
    var crashAfterCreate = false

    override fun onboard(name: String, cpf: Cpf, externalRef: String, email: Email): Onboarding {
        onboardCalls++
        if (cpf.digits in customers) return Onboarding.AlreadyExists
        val id = AccountId(UUID.randomUUID())
        customers[cpf.digits] = id
        emails[cpf.digits] = email.value
        if (crashAfterCreate) {
            crashAfterCreate = false
            throw IllegalStateException("crashed after creating in wallet-core")
        }
        return Onboarding.Created(id)
    }

    override fun accountByCpf(cpf: Cpf) = customers[cpf.digits]

    override fun contact(cpf: Cpf) = customers[cpf.digits]?.let { CoreContact("Cliente Console", emails[cpf.digits]?.let(Email::of), it) }

    /** A customer the operator opened in the console, with or without an email. */
    fun openedByConsole(cpf: String, email: String?): AccountId {
        val id = AccountId(UUID.randomUUID())
        customers[Cpf.parse(cpf).digits] = id
        email?.let { emails[Cpf.parse(cpf).digits] = it }
        return id
    }

    override fun me(accountId: AccountId) = Me("Cliente", "0001", "100", "1", "ACTIVE", 0)

    override fun statement(accountId: AccountId, before: Long?, limit: Int) = StatementPage(emptyList())

    /** Accounts reachable by number, and every transfer made (with the key it came with). */
    val byNumber = mutableMapOf<String, Pair<AccountId, TransferDestination>>()
    val transfers = mutableListOf<Pair<String, Long>>()
    var transferAnswer: PaymentOutcome? = null

    override fun findByNumber(branch: String, number: String, checkDigit: String) = byNumber["$branch/$number-$checkDigit"]

    override fun transfer(from: AccountId, to: TransferDestination, amountCents: Long, description: String?,
                          idempotencyKey: String): PaymentOutcome {
        transfers += idempotencyKey to amountCents
        return transferAnswer ?: PaymentOutcome.Done("tx-${transfers.size}", "2026-10-07T13:00:00Z")
    }
}

class InMemoryLogins : LoginRepository {
    val byCpf = mutableMapOf<String, CustomerLogin>()

    override fun find(cpf: Cpf) = byCpf[cpf.digits]

    override fun findById(id: LoginId) = byCpf.values.firstOrNull { it.id == id }

    override fun insert(login: CustomerLogin): Boolean = byCpf.putIfAbsent(login.cpf.digits, login) == null

    override fun update(login: CustomerLogin) {
        byCpf[login.cpf.digits] = login
    }

    override fun delete(id: LoginId) {
        byCpf.values.removeIf { it.id == id && it.status == LoginStatus.PENDING }
    }
}

/**
 * wallet-otp as the tests need it: keeps every code "sent", answers verifications like the real one
 * (one use, bound to subject and context), and can be told to refuse the next send for its limits.
 */
class FakeOtp : OtpGateway {
    data class Sent(val id: String, val subject: String, val purpose: OtpPurpose, val email: String, val context: String?,
                    val code: String, var used: Boolean = false)

    val sent = mutableListOf<Sent>()
    var tooSoon: Long? = null
    private var next = 100_000

    override fun send(subject: Cpf, purpose: OtpPurpose, email: Email, context: String?): OtpSend {
        tooSoon?.let { return OtpSend.TooSoon(it) }
        val s = Sent(UUID.randomUUID().toString(), subject.digits, purpose, email.value, context, (++next).toString())
        sent += s
        return OtpSend.Sent(s.id, email.masked)
    }

    override fun verify(challengeId: String, subject: Cpf, code: String, context: String?): OtpCheck {
        val s = sent.firstOrNull { it.id == challengeId && it.subject == subject.digits && it.context == context }
            ?: return OtpCheck.Refused("CHALLENGE_NOT_FOUND")
        if (s.used) return OtpCheck.Refused("CHALLENGE_ALREADY_USED")
        if (s.code != code) return OtpCheck.Refused("INVALID_CODE")
        s.used = true
        return OtpCheck.Verified
    }

    fun last() = sent.last()
}

object FakeTokens : SessionTokens {
    override fun issue(login: CustomerLogin) = IssuedToken("token-for-${login.accountId}", 1800)
}

/** Opens an account the way the desktop does: start, read the code from the "email", confirm. */
fun AuthService.signUp(otp: FakeOtp, cpf: String, name: String, email: String = "cliente@example.com"): Session {
    val started = startSignup(cpf, name, email)
    return confirmSignup(started.challengeId, otp.last().code, cpf, name, email)
}

/** Logs in the way the desktop does. */
fun AuthService.logIn(otp: FakeOtp, cpf: String): Session {
    val started = startLogin(cpf)
    return confirmLogin(started.challengeId, cpf, otp.last().code)
}

class AuthServiceTest {

    private val core = FakeCore()
    private val logins = InMemoryLogins()
    private val otp = FakeOtp()
    private val clock = object : Clock() {
        var now: Instant = Instant.parse("2026-10-09T13:00:00Z")
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val auth = AuthService(core, logins, otp, FakeTokens, StartThrottle(clock), clock)

    private val cpf = "529.982.247-25"

    private fun code(e: Throwable) = (e as DomainException).code

    private fun later(seconds: Long) {
        clock.now = clock.now.plusSeconds(seconds)
    }

    @Test
    fun `signup sends a code to the email, and only the code opens the account`() {
        val started = auth.startSignup(cpf, "Maria Silva", "Maria@Example.com")

        assertThat(started.message).contains("m***@example.com")
        assertThat(otp.last().purpose).isEqualTo(OtpPurpose.SIGNUP)
        assertThat(otp.last().email).isEqualTo("maria@example.com")
        assertThat(logins.byCpf).isEmpty() // nothing created before the code
        assertThat(core.onboardCalls).isZero()

        val session = auth.confirmSignup(started.challengeId, otp.last().code, cpf, "Maria Silva", "maria@example.com")

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.email?.value).isEqualTo("maria@example.com")
        assertThat(login.accountId).isEqualTo(core.customers["52998224725"])
        assertThat(session.customerName).isEqualTo("Maria Silva")
    }

    @Test
    fun `the signup code confirms the email it was sent to and no other`() {
        val started = auth.startSignup(cpf, "Maria Silva", "maria@example.com")

        assertThatThrownBy { auth.confirmSignup(started.challengeId, otp.last().code, cpf, "Maria Silva", "outra@example.com") }
            .extracting(::code).isEqualTo("INVALID_CODE")
        assertThat(logins.byCpf).isEmpty()
    }

    @Test
    fun `a wrong signup code opens nothing`() {
        val started = auth.startSignup(cpf, "Maria Silva", "maria@example.com")

        assertThatThrownBy { auth.confirmSignup(started.challengeId, "000000", cpf, "Maria Silva", "maria@example.com") }
            .isInstanceOf(AuthenticationException::class.java)
        assertThat(core.onboardCalls).isZero()
    }

    @Test
    fun `a CPF that already has a login is told so only after the code`() {
        auth.signUp(otp, cpf, "Maria Silva")
        val started = auth.startSignup(cpf, "Maria Silva", "maria@example.com") // no hint at the start

        assertThatThrownBy { auth.confirmSignup(started.challengeId, otp.last().code, cpf, "Maria Silva", "maria@example.com") }
            .isInstanceOf(ConflictException::class.java)
            .extracting(::code).isEqualTo("CPF_ALREADY_REGISTERED")
    }

    @Test
    fun `signup registers the confirmed email in wallet-core too`() {
        auth.signUp(otp, cpf, "Maria Silva", email = "maria@example.com")
        assertThat(core.emails["52998224725"]).isEqualTo("maria@example.com")
    }

    @Test
    fun `a console customer with an email logs in with a code sent to the operator's email, and gets linked`() {
        val account = core.openedByConsole(cpf, "maria.atendimento@example.com")

        val started = auth.startLogin(cpf)
        assertThat(otp.last().email).isEqualTo("maria.atendimento@example.com")
        val session = auth.confirmLogin(started.challengeId, cpf, otp.last().code)

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.accountId).isEqualTo(account)
        assertThat(login.email?.value).isEqualTo("maria.atendimento@example.com")
        assertThat(session.token).isEqualTo("token-for-$account")
        assertThat(core.onboardCalls).isZero()

        later(60)
        assertThat(auth.logIn(otp, cpf).customerName).isEqualTo(login.name) // from now on, an ordinary login
    }

    @Test
    fun `a console customer is never linked from a signup with an email typed by whoever asks`() {
        core.openedByConsole(cpf, "maria.atendimento@example.com")

        assertThatThrownBy { auth.signUp(otp, cpf, "Maria Silva", email = "intruso@example.com") }
            .extracting(::code).isEqualTo("CUSTOMER_EXISTS_USE_LOGIN")
        assertThat(logins.byCpf).isEmpty()

        auth.startLogin(cpf)
        assertThat(otp.last().email).isEqualTo("maria.atendimento@example.com") // never the typed one
    }

    @Test
    fun `a console customer without an email gets no code, and is sent to the operator`() {
        core.openedByConsole(cpf, null)
        val sentBefore = otp.sent.size

        val started = auth.startLogin(cpf)
        assertThat(otp.sent.size).isEqualTo(sentBefore) // nowhere to send
        assertThat(started.message).isEqualTo(auth.startLogin("111.444.777-35").message)
        assertThatThrownBy { auth.confirmLogin(started.challengeId, cpf, "100001") }.extracting(::code).isEqualTo("INVALID_CODE")

        assertThatThrownBy { auth.signUp(otp, cpf, "Maria Silva") }
            .isInstanceOf(BusinessRuleException::class.java)
            .extracting(::code).isEqualTo("CUSTOMER_EXISTS_OUTSIDE_APP")
        assertThat(logins.byCpf).isEmpty()
    }

    @Test
    fun `a signup that stopped halfway is finished by the next one, with the same account`() {
        core.crashAfterCreate = true
        assertThatThrownBy { auth.signUp(otp, cpf, "Maria Silva") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(logins.byCpf.getValue("52998224725").status).isEqualTo(LoginStatus.PENDING)

        auth.signUp(otp, cpf, "Maria Silva")

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.accountId).isEqualTo(core.customers["52998224725"]) // not a second account
    }

    @Test
    fun `login sends a code to the account's email and the code is the password`() {
        auth.signUp(otp, cpf, "Maria Silva", email = "maria@example.com")

        val started = auth.startLogin(cpf)
        assertThat(otp.last().purpose).isEqualTo(OtpPurpose.LOGIN)
        assertThat(otp.last().email).isEqualTo("maria@example.com")

        val session = auth.confirmLogin(started.challengeId, cpf, otp.last().code)
        assertThat(session.customerName).isEqualTo("Maria Silva")
    }

    @Test
    fun `login start answers the same for a CPF with and without an account`() {
        auth.signUp(otp, cpf, "Maria Silva")
        val sentBefore = otp.sent.size

        val known = auth.startLogin(cpf)
        val unknown = auth.startLogin("111.444.777-35")

        assertThat(unknown.message).isEqualTo(known.message).doesNotContain("@")
        assertThat(unknown.challengeId).isNotBlank()
        assertThat(otp.sent.size).isEqualTo(sentBefore + 1) // only the real account got an email
        assertThatThrownBy { auth.confirmLogin(unknown.challengeId, "111.444.777-35", "100001") }
            .extracting(::code).isEqualTo("INVALID_CODE")
    }

    @Test
    fun `asking again too soon is refused the same way for every CPF`() {
        auth.signUp(otp, cpf, "Maria Silva")
        auth.startLogin(cpf)
        auth.startLogin("111.444.777-35")
        later(20)

        val known = runCatching { auth.startLogin(cpf) }.exceptionOrNull() as TooManyRequestsException
        val unknown = runCatching { auth.startLogin("111.444.777-35") }.exceptionOrNull() as TooManyRequestsException
        assertThat(known.retryAfterSeconds).isEqualTo(40).isEqualTo(unknown.retryAfterSeconds)
        assertThat(known.message).isEqualTo(unknown.message).isEqualTo("Aguarde 40 segundos para pedir outro código.")

        later(40)
        auth.startLogin(cpf)
    }

    @Test
    fun `wrong, used and malformed login codes read in the customer's words`() {
        auth.signUp(otp, cpf, "Maria Silva")
        val started = auth.startLogin(cpf)

        assertThatThrownBy { auth.confirmLogin(started.challengeId, cpf, "000000") }
            .hasMessage("Código incorreto. Confira o e-mail e tente de novo.")
        auth.confirmLogin(started.challengeId, cpf, otp.last().code)
        assertThatThrownBy { auth.confirmLogin(started.challengeId, cpf, otp.last().code) }
            .extracting(::code).isEqualTo("CODE_EXPIRED")
        assertThatThrownBy { auth.confirmLogin(started.challengeId, "123", otp.last().code) }
            .extracting(::code).isEqualTo("INVALID_CODE")
    }

    @Test
    fun `wallet-otp's own limit reaches the customer as a wait`() {
        otp.tooSoon = 42
        assertThatThrownBy { auth.startSignup(cpf, "Maria Silva", "maria@example.com") }
            .isInstanceOf(TooManyRequestsException::class.java)
            .hasMessage("Aguarde 42 segundos para pedir outro código.")
    }

    @Test
    fun `invalid data is refused before any code is sent`() {
        assertThatThrownBy { auth.startSignup(cpf, "Maria Silva", "maria@") }.extracting(::code).isEqualTo("INVALID_EMAIL")
        assertThatThrownBy { auth.startSignup(cpf, "M", "maria@example.com") }.extracting(::code).isEqualTo("INVALID_NAME")
        assertThatThrownBy { auth.startSignup("123", "Maria Silva", "maria@example.com") }.extracting(::code).isEqualTo("INVALID_CPF")
        assertThat(otp.sent).isEmpty()
    }
}

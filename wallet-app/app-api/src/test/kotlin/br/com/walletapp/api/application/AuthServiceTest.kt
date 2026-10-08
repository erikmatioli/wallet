package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.IssuedToken
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.Onboarding
import br.com.walletapp.api.application.port.PaymentOutcome
import br.com.walletapp.api.application.port.PasswordHasher
import br.com.walletapp.api.application.port.SessionTokens
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.AuthenticationException
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.ConflictException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.DomainException
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.LoginStatus
import br.com.walletapp.api.domain.Password
import br.com.walletapp.contract.Me
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

    /** Simulates app-api dying right after wallet-core created the customer. */
    var crashAfterCreate = false

    override fun onboard(name: String, cpf: Cpf, externalRef: String): Onboarding {
        onboardCalls++
        if (cpf.digits in customers) return Onboarding.AlreadyExists
        val id = AccountId(UUID.randomUUID())
        customers[cpf.digits] = id
        if (crashAfterCreate) {
            crashAfterCreate = false
            throw IllegalStateException("crashed after creating in wallet-core")
        }
        return Onboarding.Created(id)
    }

    override fun accountByCpf(cpf: Cpf) = customers[cpf.digits]

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

/** Not BCrypt (slow on purpose): a reversible stand-in, enough to tell right from wrong. */
object PlainHasher : PasswordHasher {
    override fun hash(password: Password) = "h:" + password.value
    override fun matches(raw: String, hash: String) = hash == "h:$raw"
}

object FakeTokens : SessionTokens {
    override fun issue(login: CustomerLogin) = IssuedToken("token-for-${login.accountId}", 1800)
}

class AuthServiceTest {

    private val core = FakeCore()
    private val logins = InMemoryLogins()
    private val clock = object : Clock() {
        var now: Instant = Instant.parse("2026-10-07T13:00:00Z")
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val auth = AuthService(core, logins, PlainHasher, FakeTokens, clock)

    private val cpf = "529.982.247-25"

    private fun code(e: Throwable) = (e as DomainException).code

    @Test
    fun `signup opens the account in wallet-core and logs the customer in`() {
        val session = auth.signup(cpf, "Maria Silva", "senha1234")

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.accountId).isEqualTo(core.customers["52998224725"])
        assertThat(login.passwordHash).isNotEqualTo("senha1234")
        assertThat(session.customerName).isEqualTo("Maria Silva")
        assertThat(session.token).isEqualTo("token-for-${login.accountId}")
    }

    @Test
    fun `a CPF that already has a login is sent to the login`() {
        auth.signup(cpf, "Maria Silva", "senha1234")
        assertThatThrownBy { auth.signup(cpf, "Maria Silva", "outra1234") }
            .isInstanceOf(ConflictException::class.java)
            .extracting(::code).isEqualTo("CPF_ALREADY_REGISTERED")
    }

    @Test
    fun `a customer created outside the app is not linked, and leaves no login behind`() {
        core.customers["52998224725"] = AccountId(UUID.randomUUID()) // e.g. onboarded by the console

        assertThatThrownBy { auth.signup(cpf, "Maria Silva", "senha1234") }
            .isInstanceOf(BusinessRuleException::class.java)
            .extracting(::code).isEqualTo("CUSTOMER_EXISTS_OUTSIDE_APP")
        assertThat(logins.byCpf).isEmpty()
    }

    @Test
    fun `a customer created outside the app logs in the first time with the CPF as the password`() {
        val account = AccountId(UUID.randomUUID())
        core.customers["52998224725"] = account // e.g. onboarded by the console

        assertThat(auth.login(cpf, "52998224725").customerName).isEqualTo("Cliente")

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.accountId).isEqualTo(account)
        assertThat(auth.login(cpf, "52998224725").customerName).isEqualTo("Cliente") // the same login, again
        assertThat(core.onboardCalls).isZero()
    }

    @Test
    fun `the CPF as the password only works for a customer wallet-core has, and only before there is a login`() {
        assertThatThrownBy { auth.login(cpf, "52998224725") }.isInstanceOf(AuthenticationException::class.java)
        assertThat(logins.byCpf).isEmpty()

        core.customers["52998224725"] = AccountId(UUID.randomUUID())
        assertThatThrownBy { auth.login(cpf, "outra1234") }.isInstanceOf(AuthenticationException::class.java)
        assertThat(logins.byCpf).isEmpty()

        auth.signup("111.444.777-35", "Joana Souza", "senha1234")
        assertThatThrownBy { auth.login("111.444.777-35", "11144477735") }
            .isInstanceOf(AuthenticationException::class.java)
    }

    @Test
    fun `a signup that stopped halfway is finished by the next one, with the same account`() {
        core.crashAfterCreate = true
        assertThatThrownBy { auth.signup(cpf, "Maria Silva", "senha1234") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(logins.byCpf.getValue("52998224725").status).isEqualTo(LoginStatus.PENDING)

        auth.signup(cpf, "Maria Silva", "senha1234")

        val login = logins.byCpf.getValue("52998224725")
        assertThat(login.status).isEqualTo(LoginStatus.ACTIVE)
        assertThat(login.accountId).isEqualTo(core.customers["52998224725"]) // not a second account
    }

    @Test
    fun `login answers the same for an unknown CPF and a wrong password`() {
        auth.signup(cpf, "Maria Silva", "senha1234")

        val unknown = runCatching { auth.login("111.444.777-35", "senha1234") }.exceptionOrNull()!!
        val wrong = runCatching { auth.login(cpf, "errada1234") }.exceptionOrNull()!!
        val malformed = runCatching { auth.login("123", "senha1234") }.exceptionOrNull()!!

        listOf(unknown, wrong, malformed).forEach {
            assertThat(it).isInstanceOf(AuthenticationException::class.java).hasMessage("CPF ou senha inválidos.")
        }
        assertThat(auth.login(cpf, "senha1234").customerName).isEqualTo("Maria Silva")
    }

    @Test
    fun `five wrong passwords lock the login, even for the right one, for 15 minutes`() {
        auth.signup(cpf, "Maria Silva", "senha1234")
        repeat(5) { runCatching { auth.login(cpf, "errada1234") } }

        assertThatThrownBy { auth.login(cpf, "senha1234") }.extracting(::code).isEqualTo("LOGIN_LOCKED")

        clock.now = clock.now.plus(CustomerLogin.LOCK)
        assertThat(auth.login(cpf, "senha1234").customerName).isEqualTo("Maria Silva")
    }
}

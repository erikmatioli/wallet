package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.PaymentOutcome
import br.com.walletapp.api.application.port.PixGateway
import br.com.walletapp.api.application.port.PixOutcome
import br.com.walletapp.api.application.port.SentPix
import br.com.walletapp.api.application.port.SentPixRepository
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.DomainException
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.NotFoundException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.PixPayee
import br.com.walletapp.contract.PixRequest
import br.com.walletapp.contract.TransferDestination
import br.com.walletapp.contract.TransferRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FakePix : PixGateway {
    val sends = mutableListOf<Triple<String, String, String>>() // key, payer CPF, payee
    var answer: PixOutcome? = null
    val statuses = mutableMapOf<String, PixOutcome.Sent>()

    override fun send(payer: AccountId, payerCpf: Cpf, payee: PixPayee, amountCents: Long, description: String?,
                      idempotencyKey: String): PixOutcome {
        sends += Triple(idempotencyKey, payerCpf.digits, payee.name)
        return answer ?: PixOutcome.Sent("E12345678202610071300${sends.size.toString().padStart(11, '0')}", "SENT", null)
    }

    override fun status(endToEndId: String) = statuses[endToEndId]
}

class InMemorySentPix : SentPixRepository {
    val all = mutableListOf<SentPix>()
    override fun record(pix: SentPix) {
        if (all.none { it.endToEndId == pix.endToEndId }) all += pix
    }
    override fun find(loginId: LoginId, endToEndId: String) = all.firstOrNull { it.loginId == loginId && it.endToEndId == endToEndId }
}

class PaymentServiceTest {

    private val core = FakeCore()
    private val pix = FakePix()
    private val logins = InMemoryLogins()
    private val sentPix = InMemorySentPix()
    private val auth = AuthService(core, logins, PlainHasher, FakeTokens, java.time.Clock.systemUTC())
    private val payments = PaymentService(core, pix, logins, sentPix)

    private val joao = AccountId(UUID.randomUUID())
    private val payee = PixPayee("99999999", "0042", "1234565", "111.444.777-35", "Fulano Externo")

    private fun customer(cpf: String): Pair<LoginId, AccountId> {
        auth.signup(cpf, "Cliente $cpf", "senha1234")
        val login = logins.find(Cpf.parse(cpf))!!
        return login.id to login.accountId!!
    }

    private fun code(e: Throwable) = (e as DomainException).code

    init {
        core.byNumber["0001/200-2"] = joao to TransferDestination("João Souza", "0001", "200", "2")
    }

    @Test
    fun `a transfer goes from the token's account to the account the number points to`() {
        val (login, account) = customer("52998224725")

        val receipt = payments.transfer(login, account, TransferRequest("0001", "200", "2", 1_500, " aluguel "), "k1")

        assertThat(receipt.destination.holderName).isEqualTo("João Souza")
        assertThat(receipt.amountCents).isEqualTo(1_500)
        assertThat(receipt.description).isEqualTo("aluguel")
    }

    @Test
    fun `the same desktop key from two customers never collides downstream, and repeats for the same one`() {
        val (maria, mariaAccount) = customer("52998224725")
        val (ana, anaAccount) = customer("39053344705")
        val r = TransferRequest("0001", "200", "2", 100)

        payments.transfer(maria, mariaAccount, r, "same-key")
        payments.transfer(ana, anaAccount, r, "same-key")
        payments.transfer(maria, mariaAccount, r, "same-key") // a retry of Maria's

        val keys = core.transfers.map { it.first }
        assertThat(keys[0]).isNotEqualTo(keys[1]).startsWith("app-").hasSize(44)
        assertThat(keys[2]).isEqualTo(keys[0])
    }

    @Test
    fun `refusals come back with the customer's message`() {
        val (login, account) = customer("52998224725")
        core.transferAnswer = PaymentOutcome.Refused("INSUFFICIENT_FUNDS")

        assertThatThrownBy { payments.transfer(login, account, TransferRequest("0001", "200", "2", 100), "k") }
            .isInstanceOf(BusinessRuleException::class.java).hasMessage("Saldo insuficiente.")
        assertThatThrownBy { payments.transfer(login, account, TransferRequest("0001", "999", "9", 100), "k2") }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { payments.transfer(login, account, TransferRequest("0001", "200", "2", 0), "k3") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { payments.transfer(login, account, TransferRequest("0001", "200", "2", 100), null) }
            .extracting(::code).isEqualTo("IDEMPOTENCY_KEY_REQUIRED")
    }

    @Test
    fun `a transfer to one's own account is refused before anything moves`() {
        val (login, account) = customer("52998224725")
        core.byNumber["0001/100-1"] = account to TransferDestination("Eu", "0001", "100", "1")

        assertThatThrownBy { payments.destination(account, "0001", "100", "1") }.extracting(::code).isEqualTo("SAME_ACCOUNT")
        assertThat(core.transfers).isEmpty()
    }

    @Test
    fun `a Pix is sent with the login's CPF, and only its sender can follow it`() {
        val (maria, mariaAccount) = customer("52998224725")
        val (ana, _) = customer("39053344705")

        val receipt = payments.sendPix(maria, mariaAccount, PixRequest(payee, 2_000), "k1")

        assertThat(pix.sends.single().second).isEqualTo("52998224725") // never a CPF from the request
        assertThat(receipt.status).isEqualTo("SENT")
        pix.statuses[receipt.endToEndId] = PixOutcome.Sent(receipt.endToEndId, "REFUNDED", "AC03")

        val later = payments.pixStatus(maria, receipt.endToEndId)
        assertThat(later.status).isEqualTo("REFUNDED")
        assertThat(later.reasonMessage).isEqualTo("Conta do recebedor inexistente ou inválida.")
        assertThat(later.payeeName).isEqualTo("Fulano Externo")
        assertThatThrownBy { payments.pixStatus(ana, receipt.endToEndId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a Pix refused before sending says why`() {
        val (login, account) = customer("52998224725")
        pix.answer = PixOutcome.Refused("POLICY_MAX_AMOUNT")

        assertThatThrownBy { payments.sendPix(login, account, PixRequest(payee, 999_999_00), "k") }
            .hasMessage("Valor acima do limite por Pix.")
        assertThat(sentPix.all).isEmpty()
    }
}

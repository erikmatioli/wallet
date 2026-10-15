package br.com.walletapp.api.adapter.out.core

import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.contract.CounterpartyAccount
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.PaymentNotConfirmedException
import br.com.walletapp.contract.DayBalance
import br.com.walletapp.contract.TransferDestination
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The translation of wallet-core's JSON into the app's contract, against a fake wallet-core on a real
 * port (the client sets its own request factory, so a mocked one would not be used).
 */
class WalletCoreClientTest {

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(path: String, json: String) = server.createContext(path) { ex ->
        val body = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun client(readTimeout: Duration = Duration.ofSeconds(5)): WalletCoreClient {
        server.start()
        val base = URI.create("http://localhost:${server.address.port}")
        val props = AppProperties(
            AppProperties.Session("test-secret-0123456789abcdef0123456789"),
            AppProperties.WalletCore(base, "demo-tenant", "secret", readTimeout = readTimeout),
            AppProperties.Pix(base), AppProperties.Scheduler(base), AppProperties.Otp(base),
        )
        return WalletCoreClient(RestClient.builder(), props, CoreTokens(RestClient.builder(), props, Clock.systemUTC()))
    }

    @Test
    fun `the statement brings the counterparty's account - the transfer's and the Pix's`() {
        val account = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        respond("/v1/accounts/$account/statement", """
            {"entries":[
              {"transactionId":"${UUID.randomUUID()}","sequence":3,"type":"TRANSFER","direction":"DEBIT",
               "amount":10.00,"balanceAfter":40.00,"description":"Almoço","occurredAt":"2026-10-09T23:41:00Z",
               "counterpartyAccountId":"${UUID.randomUUID()}","counterpartyCustomerName":"Joao Souza",
               "counterpartyAccountFormatted":"0001-00100002-9","pix":null},
              {"transactionId":"${UUID.randomUUID()}","sequence":2,"type":"PIX_IN","direction":"CREDIT",
               "amount":250.00,"balanceAfter":50.00,"description":"Pix recebido","occurredAt":"2026-10-09T23:31:00Z",
               "counterpartyAccountId":null,"counterpartyCustomerName":null,"counterpartyAccountFormatted":null,
               "pix":{"endToEndId":"E999","returnId":null,"relatedTransactionId":null,"reasonCode":null,"remittanceInfo":null,
                      "counterparty":{"name":"Ana Externa","taxIdMasked":"***7735","ispb":"99999999",
                                      "branch":"0042","account":"1234565","accountType":"TRAN"}}},
              {"transactionId":"${UUID.randomUUID()}","sequence":1,"type":"DEPOSIT","direction":"CREDIT",
               "amount":1.00,"balanceAfter":1.00,"description":"","occurredAt":"2026-10-09T13:14:00Z",
               "counterpartyAccountId":null,"counterpartyCustomerName":null,"counterpartyAccountFormatted":null,"pix":null}
            ],"nextBefore":null}
        """.trimIndent())

        val (transfer, pix, deposit) = client().statement(AccountId(account), null, 20).entries

        assertThat(transfer.counterpartyName).isEqualTo("Joao Souza")
        assertThat(transfer.counterpartyAccount).isEqualTo(CounterpartyAccount("0001", "00100002", "9"))
        assertThat(pix.counterpartyName).isEqualTo("Ana Externa")
        assertThat(pix.counterpartyAccount).isNull()
        assertThat(pix.pix!!.counterpartyInstitution).isEqualTo("99999999")
        assertThat(pix.pix!!.counterpartyBranch).isEqualTo("0042")
        assertThat(pix.pix!!.counterpartyAccount).isEqualTo("1234565")
        assertThat(pix.pix!!.counterpartyTaxIdMasked).isEqualTo("***7735")
        assertThat(deposit.counterpartyAccount).isNull()
        assertThat(deposit.pix).isNull()
    }

    @Test
    fun `an account in a format it does not know is left out, not guessed`() {
        val account = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        respond("/v1/accounts/$account/statement", """
            {"entries":[{"transactionId":"${UUID.randomUUID()}","sequence":1,"type":"TRANSFER","direction":"CREDIT",
              "amount":1.00,"balanceAfter":1.00,"description":null,"occurredAt":"2026-10-09T13:14:00Z",
              "counterpartyAccountId":"${UUID.randomUUID()}","counterpartyCustomerName":"Joao Souza",
              "counterpartyAccountFormatted":"0001 / 00100002-9","pix":null}],"nextBefore":null}
        """.trimIndent())

        val entry = client().statement(AccountId(account), null, 20).entries.single()

        assertThat(entry.counterpartyName).isEqualTo("Joao Souza")
        assertThat(entry.counterpartyAccount).isNull()
    }

    @Test
    fun `daily balances come in cents, and a day not closed is a business refusal`() {
        val account = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        respond("/v1/accounts/$account/daily-balances", """
            {"accountId":"$account","currency":"BRL","days":[
              {"date":"2026-10-08","balance":0.00,"lastSequence":0},
              {"date":"2026-10-09","balance":150.75,"lastSequence":9}]}
        """.trimIndent())

        val days = client().dailyBalances(AccountId(account), LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-09"))

        assertThat(days).containsExactly(DayBalance("2026-10-08", 0), DayBalance("2026-10-09", 15_075))
    }

    @Test
    fun `wallet-core saying the day is not over becomes DAY_NOT_CLOSED`() {
        val account = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        server.createContext("/v1/accounts/$account/daily-balances") { ex ->
            val body = """{"code":"DAY_NOT_CLOSED","detail":"not yet"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/problem+json")
            ex.sendResponseHeaders(422, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        assertThatThrownBy { client().dailyBalances(AccountId(account), LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-09")) }
            .isInstanceOf(BusinessRuleException::class.java)
            .hasMessageContaining("meia-noite")
    }

    /** Answers after [delayMillis]: before the headers, or (with [bodyLate]) between the headers and the body. */
    private fun slow(path: String, json: String, delayMillis: Long, bodyLate: Boolean) = server.createContext(path) { ex ->
        val body = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        if (bodyLate) {
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.flush()
            Thread.sleep(delayMillis)
        } else {
            Thread.sleep(delayMillis)
            ex.sendResponseHeaders(200, 0)
        }
        runCatching { ex.responseBody.use { it.write(body) } }
    }

    @Test
    fun `a transfer whose answer is lost is not confirmed, never a 500 - before the headers or in the body`() {
        val transaction = """{"id":"${UUID.randomUUID()}","occurredAt":"2026-10-15T20:00:00Z"}"""
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        slow("/v1/transfers", transaction, 1_500, bodyLate = true)
        val client = client(readTimeout = Duration.ofMillis(400))

        assertThatThrownBy {
            client.transfer(AccountId(UUID.randomUUID()), TransferDestination("Joao Souza", "0001", "00100002", "9"), 1_000, null, "k-1")
        }.isInstanceOfSatisfying(PaymentNotConfirmedException::class.java) {
            assertThat(it.code).isEqualTo("PAYMENT_NOT_CONFIRMED")
        }
    }

    @Test
    fun `a slow read that moves no money is only unavailable`() {
        val account = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        slow("/v1/accounts/$account", """{"branch":"0001"}""", 1_500, bodyLate = false)

        assertThatThrownBy { client(readTimeout = Duration.ofMillis(400)).me(AccountId(account)) }
            .isInstanceOfSatisfying(DependencyUnavailableException::class.java) {
                assertThat(it).isNotInstanceOf(PaymentNotConfirmedException::class.java)
                assertThat(it.code).isEqualTo("TEMPORARILY_UNAVAILABLE")
            }
    }

    @Test
    fun `an account blocked only for fees is told apart from any other block`() {
        val feesOnly = UUID.randomUUID()
        val feesAndFraud = UUID.randomUUID()
        respond("/v1/auth/token", """{"access_token":"t","token_type":"Bearer","expires_in":600}""")
        respond("/v1/accounts/$feesOnly", account("""[{"id":"${UUID.randomUUID()}","type":"FEE_PENDING","active":true}]"""))
        respond("/v1/accounts/$feesAndFraud", account("""[{"id":"${UUID.randomUUID()}","type":"FEE_PENDING","active":true},
            {"id":"${UUID.randomUUID()}","type":"FRAUD","active":true}]"""))
        val client = client()

        assertThat(client.me(AccountId(feesOnly)).blockedForFees).isTrue()
        assertThat(client.me(AccountId(feesAndFraud)).blockedForFees).isFalse()
    }

    private fun account(blocks: String) = """
        {"branch":"0001","number":"00100001","checkDigit":"7","status":"ACTIVE","balance":0.00,
         "customerName":"Ana Lima","canPay":false,"canReceive":true,"blocks":$blocks}
    """.trimIndent()
}

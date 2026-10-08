package br.com.walletapp.api.adapter.out.scheduler

import br.com.walletapp.api.adapter.out.core.CoreTokens
import br.com.walletapp.api.application.CustomerMessages
import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.ScheduleOutcome
import br.com.walletapp.api.application.port.ScheduleView
import br.com.walletapp.api.application.port.SchedulerGateway
import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleAttempt
import br.com.walletapp.contract.ScheduleExecution
import br.com.walletapp.contract.ScheduleRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * wallet-scheduler's API (its ADR-001), as the app's tenant. It accepts wallet-core's tokens - the
 * tenant's full token carries schedules:read and schedules:write. Its JSON is read with Jackson into the
 * private classes below and translated into the app's contract here.
 */
@Component
class SchedulerClient(builder: RestClient.Builder, props: AppProperties, private val tokens: CoreTokens) : SchedulerGateway {

    private val http: RestClient = builder
        .baseUrl(props.scheduler.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.scheduler.connectTimeout)
            setReadTimeout(props.scheduler.readTimeout)
        })
        .build()

    // ---- wallet-scheduler's JSON
    private data class Reason(val code: String, val message: String)
    private data class Destination(val branch: String, val number: String, val checkDigit: String, val holderName: String)
    private data class Payee(val ispb: String, val branch: String, val accountNumber: String, val name: String)
    private data class Execution(val status: String, val attemptCount: Int, val nextAttemptAt: Instant?,
                                 val transactionId: UUID?, val endToEndId: String?, val failure: Reason?)
    private data class Attempt(val startedAt: Instant, val outcome: String?, val reason: Reason?)
    private data class Sched(val id: UUID, val type: String, val status: String, val payerAccountId: UUID,
                             val destination: Destination?, val pixPayee: Payee?, val amount: BigDecimal,
                             val description: String?, val executeOn: LocalDate, val createdAt: Instant,
                             val execution: Execution, val attempts: List<Attempt>)
    private data class Problem(val code: String?)

    private data class Target(val branch: String, val number: String, val checkDigit: String)
    private data class PixPayee(val ispb: String, val branch: String, val accountNumber: String, val taxId: String, val name: String)
    private data class PixPart(val payerTaxId: String, val payee: PixPayee)
    private data class Create(val type: String, val payerAccountId: UUID, val executeOn: String, val amount: BigDecimal,
                              val description: String?, val destination: Target?, val pix: PixPart?)

    override fun list(payer: AccountId): List<ScheduleView> = call("list") { token ->
        http.get().uri("/v1/schedules?payerAccountId={p}", payer.value)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve().body<List<Sched>>().orEmpty().map(::view)
    }

    override fun get(id: String): ScheduleView? = call("get") { token ->
        try {
            http.get().uri("/v1/schedules/{id}", UUID.fromString(id))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body<Sched>()?.let(::view)
        } catch (e: HttpClientErrorException.NotFound) {
            null
        } catch (e: IllegalArgumentException) {
            null // not even a UUID
        }
    }

    override fun create(payer: AccountId, payerCpf: Cpf, request: ScheduleRequest, idempotencyKey: String): ScheduleOutcome =
        change("create") { token ->
            val body = Create(
                type = request.type,
                payerAccountId = payer.value,
                executeOn = request.executeOn,
                amount = BigDecimal.valueOf(request.amountCents, 2),
                description = request.description?.trim()?.ifEmpty { null },
                destination = request.transfer?.let { Target(it.branch, it.number, it.checkDigit) },
                pix = request.pix?.let {
                    PixPart(payerCpf.digits, PixPayee(it.ispb, it.branch, it.accountNumber,
                        it.taxId.filter(Char::isLetterOrDigit), it.name))
                },
            )
            http.post().uri("/v1/schedules")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve().body<Sched>()!!
        }

    override fun cancel(id: String): ScheduleOutcome = change("cancel") { token ->
        http.post().uri("/v1/schedules/{id}/cancel", UUID.fromString(id))
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve().body<Sched>()!!
    }

    private fun change(operation: String, request: (String) -> Sched): ScheduleOutcome = call(operation) { token ->
        try {
            ScheduleOutcome.Done(view(request(token)))
        } catch (e: HttpClientErrorException) {
            if (e is HttpClientErrorException.Unauthorized) throw e
            val code = runCatching { e.getResponseBodyAs(Problem::class.java)?.code }.getOrNull()
            ScheduleOutcome.Refused(code ?: "HTTP_${e.statusCode.value()}")
        }
    }

    private fun view(s: Sched): ScheduleView {
        val (payee, detail) = s.destination?.let { it.holderName to "${it.branch} / ${it.number}-${it.checkDigit}" }
            ?: s.pixPayee!!.let { it.name to "ISPB ${it.ispb} · ${it.branch} / ${it.accountNumber}" }
        val e = s.execution
        return ScheduleView(
            AccountId(s.payerAccountId),
            Schedule(
                id = s.id.toString(),
                type = s.type,
                status = s.status,
                executeOn = s.executeOn.toString(),
                amountCents = s.amount.movePointRight(2).longValueExact(),
                description = s.description,
                payee = payee,
                payeeDetail = detail,
                createdAt = s.createdAt.toString(),
                canCancel = false, // ScheduleService decides
                execution = ScheduleExecution(e.status, e.attemptCount, e.nextAttemptAt?.toString(),
                    e.failure?.let(::message),
                    e.transactionId?.toString(), e.endToEndId),
                attempts = s.attempts.map { a ->
                    ScheduleAttempt(a.startedAt.toString(), a.outcome, a.reason?.let(::message))
                },
            ),
        )
    }

    /** The app's wording when it has one; otherwise wallet-scheduler's, already written for the customer. */
    private fun message(r: Reason) = CustomerMessages.known(r.code) ?: r.message

    private fun <T> call(operation: String, request: (String) -> T): T = try {
        tokens.withToken(null, request)
    } catch (e: HttpServerErrorException) {
        throw DependencyUnavailableException("wallet-scheduler $operation failed (${e.statusCode.value()})", e)
    } catch (e: ResourceAccessException) {
        throw DependencyUnavailableException("wallet-scheduler $operation failed: ${e.message}", e)
    }
}

package br.com.walletapp.desktop.data

import br.com.walletapp.contract.AppError
import br.com.walletapp.contract.AppInfo
import br.com.walletapp.contract.LoginRequest
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.PixReceipt
import br.com.walletapp.contract.PixRequest
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleRequest
import br.com.walletapp.contract.Session
import br.com.walletapp.contract.SignupRequest
import br.com.walletapp.contract.StatementPage
import br.com.walletapp.contract.TransferDestination
import br.com.walletapp.contract.TransferReceipt
import br.com.walletapp.contract.TransferRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** An answer of app-api that is not a success: its [code], and a [message] made to be shown as it is. */
class AppApiException(val code: String, message: String) : RuntimeException(message)

/**
 * The only door to the outside: app-api, nothing else (ADR-001, decision 1). Every call is a `suspend`
 * function - it never blocks the UI thread. The customer's token is read from [session] on each call.
 *
 * @param engine the HTTP engine; tests pass a mock one
 */
class AppApiClient(baseUrl: String, private val session: SessionStore, engine: HttpClientEngine = Java.create()) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(engine) {
        defaultRequest { url(baseUrl) }
        // The contract's @Serializable classes, read with the same library app-api writes them with.
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 15_000
        }
    }

    suspend fun info(): AppInfo = http.get("app/v1/info").read()

    suspend fun signup(cpf: String, name: String, password: String): Session =
        http.post("app/v1/signup") { jsonBody(SignupRequest(cpf, name, password)) }.read()

    suspend fun login(cpf: String, password: String): Session =
        http.post("app/v1/login") { jsonBody(LoginRequest(cpf, password)) }.read()

    suspend fun me(): Me = http.get("app/v1/me") { auth() }.read()

    suspend fun statement(before: Long? = null, limit: Int = 20): StatementPage = http.get("app/v1/statement") {
        auth()
        parameter("limit", limit)
        before?.let { parameter("before", it) }
    }.read()

    suspend fun transferDestination(branch: String, number: String, checkDigit: String): TransferDestination =
        http.get("app/v1/transfers/destination") {
            auth()
            parameter("branch", branch)
            parameter("number", number)
            parameter("checkDigit", checkDigit)
        }.read()

    /** [idempotencyKey]: created on the confirmation screen and repeated on a retry, so it never pays twice. */
    suspend fun transfer(request: TransferRequest, idempotencyKey: String): TransferReceipt =
        http.post("app/v1/transfers") { auth(); idempotent(idempotencyKey); jsonBody(request) }.read()

    suspend fun sendPix(request: PixRequest, idempotencyKey: String): PixReceipt =
        http.post("app/v1/pix") { auth(); idempotent(idempotencyKey); jsonBody(request) }.read()

    suspend fun pixStatus(endToEndId: String): PixReceipt = http.get("app/v1/pix/$endToEndId") { auth() }.read()

    suspend fun schedules(): List<Schedule> = http.get("app/v1/schedules") { auth() }.read()

    suspend fun createSchedule(request: ScheduleRequest, idempotencyKey: String): Schedule =
        http.post("app/v1/schedules") { auth(); idempotent(idempotencyKey); jsonBody(request) }.read()

    suspend fun cancelSchedule(id: String): Schedule = http.post("app/v1/schedules/$id/cancel") { auth() }.read()

    private fun HttpRequestBuilder.idempotent(key: String) = header("Idempotency-Key", key)

    private fun HttpRequestBuilder.auth() {
        bearerAuth(session.token ?: throw AppApiException("SESSION_EXPIRED", "Sua sessão expirou. Entre de novo."))
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /**
     * The body as [T] on success; otherwise the contract's AppError as an exception. An expired session
     * also clears the token, so the app goes back to the login screen (see SessionStore).
     */
    private suspend inline fun <reified T> HttpResponse.read(): T {
        if (status.isSuccess()) return body()
        val error = runCatching { json.decodeFromString<AppError>(bodyAsText()) }.getOrNull()
            ?: AppError("HTTP_${status.value}", "Não foi possível falar com o servidor (${status.value}).")
        if (error.code == "SESSION_EXPIRED") session.expire(error.message)
        throw AppApiException(error.code, error.message)
    }
}

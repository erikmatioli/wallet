package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.application.PaymentService
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.contract.PixReceipt
import br.com.walletapp.contract.PixRequest
import br.com.walletapp.contract.TransferDestination
import br.com.walletapp.contract.TransferReceipt
import br.com.walletapp.contract.TransferRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Transfers and Pix of the logged-in customer. The payer account comes from the token, and the CPF for a
 * Pix from the login: the request carries neither.
 */
@RestController
@RequestMapping("/app/v1")
class PaymentController(private val payments: PaymentService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/transfers/destination")
    fun destination(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam branch: String,
        @RequestParam number: String,
        @RequestParam checkDigit: String,
    ): TransferDestination = asCustomer(jwt) { payments.destination(it, branch, number, checkDigit) }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody r: TransferRequest,
    ): TransferReceipt = asCustomer(jwt) { account ->
        payments.transfer(login(jwt), account, r, key)
            .also { log.info("transfer made: transaction={} amountCents={}", it.transactionId, it.amountCents) }
    }

    @PostMapping("/pix")
    @ResponseStatus(HttpStatus.CREATED)
    fun pix(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody r: PixRequest,
    ): PixReceipt = asCustomer(jwt) { account ->
        payments.sendPix(login(jwt), account, r, key)
            .also { log.info("pix sent: endToEndId={} status={} amountCents={}", it.endToEndId, it.status, it.amountCents) }
    }

    @GetMapping("/pix/{endToEndId}")
    fun pixStatus(@AuthenticationPrincipal jwt: Jwt, @PathVariable endToEndId: String): PixReceipt =
        asCustomer(jwt) { payments.pixStatus(login(jwt), endToEndId) }

    private fun login(jwt: Jwt) = LoginId(UUID.fromString(jwt.subject))
}

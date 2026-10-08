package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.PaymentOutcome
import br.com.walletapp.api.application.port.PixGateway
import br.com.walletapp.api.application.port.PixOutcome
import br.com.walletapp.api.application.port.SentPix
import br.com.walletapp.api.application.port.SentPixRepository
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.NotFoundException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.PixReceipt
import br.com.walletapp.contract.PixRequest
import br.com.walletapp.contract.TransferDestination
import br.com.walletapp.contract.TransferReceipt
import br.com.walletapp.contract.TransferRequest

/**
 * Transfers and Pix of the logged-in customer. The payer is always the token's account ([AccountId]) and,
 * for a Pix, the login's CPF: the desktop sends neither.
 */
class PaymentService(
    private val core: CoreBanking,
    private val pix: PixGateway,
    private val logins: LoginRepository,
    private val sentPix: SentPixRepository,
) {

    /** Who receives, for the confirmation screen - before any money moves. */
    fun destination(payer: AccountId, branch: String, number: String, checkDigit: String): TransferDestination {
        val (account, destination) = core.findByNumber(branch, number, checkDigit)
            ?: throw NotFoundException("DESTINATION_ACCOUNT_NOT_FOUND", CustomerMessages.of("DESTINATION_ACCOUNT_NOT_FOUND"))
        if (account == payer) throw BusinessRuleException("SAME_ACCOUNT", CustomerMessages.of("SAME_ACCOUNT"))
        return destination
    }

    fun transfer(login: LoginId, payer: AccountId, r: TransferRequest, idempotencyKey: String?): TransferReceipt {
        val key = customerKey(login, idempotencyKey)
        validate(r.amountCents, r.description)
        val destination = destination(payer, r.branch, r.number, r.checkDigit)
        return when (val o = core.transfer(payer, destination, r.amountCents, r.description?.trim(), key)) {
            is PaymentOutcome.Done -> TransferReceipt(o.id, r.amountCents, o.occurredAt, destination, r.description?.trim())
            is PaymentOutcome.Refused -> throw BusinessRuleException(o.code, CustomerMessages.of(o.code))
        }
    }

    fun sendPix(login: LoginId, payer: AccountId, r: PixRequest, idempotencyKey: String?): PixReceipt {
        val key = customerKey(login, idempotencyKey)
        validate(r.amountCents, r.description)
        val cpf = logins.findById(login)?.cpf ?: throw IllegalStateException("token of a login that does not exist")
        return when (val o = pix.send(payer, cpf, r.payee, r.amountCents, r.description?.trim(), key)) {
            is PixOutcome.Sent -> {
                // Recorded before answering: from now on this customer, and only this one, can follow this Pix.
                sentPix.record(SentPix(login, o.endToEndId, r.payee.name, r.payee.ispb, r.amountCents))
                receipt(o, r.payee.name, r.payee.ispb, r.amountCents)
            }
            is PixOutcome.Refused -> throw BusinessRuleException(o.code, CustomerMessages.of(o.code))
        }
    }

    /** Only the customer who sent it: another customer's EndToEndId is simply not found. */
    fun pixStatus(login: LoginId, endToEndId: String): PixReceipt {
        val mine = sentPix.find(login, endToEndId)
            ?: throw NotFoundException("PIX_NOT_FOUND", "Pix não encontrado.")
        val status = pix.status(endToEndId) ?: throw NotFoundException("PIX_NOT_FOUND", "Pix não encontrado.")
        return receipt(status, mine.payeeName, mine.payeeIspb, mine.amountCents)
    }

    private fun receipt(o: PixOutcome.Sent, payeeName: String, payeeIspb: String, amountCents: Long) =
        PixReceipt(o.endToEndId, o.status, amountCents, payeeName, payeeIspb, o.reasonCode,
            o.reasonCode?.let(CustomerMessages::of))

    private fun validate(amountCents: Long, description: String?) {
        if (amountCents <= 0) throw ValidationException("INVALID_AMOUNT", "Informe um valor maior que zero.")
        if ((description?.trim()?.length ?: 0) > 140) {
            throw ValidationException("INVALID_DESCRIPTION", "A descrição pode ter até 140 caracteres.")
        }
    }

    private fun customerKey(login: LoginId, key: String?) = IdempotencyKeys.forCustomer(login, key)
}

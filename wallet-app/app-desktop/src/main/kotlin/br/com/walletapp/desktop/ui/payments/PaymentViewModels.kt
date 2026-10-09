package br.com.walletapp.desktop.ui.payments

import br.com.walletapp.contract.PixPayee
import br.com.walletapp.contract.PixReceipt
import br.com.walletapp.contract.PixRequest
import br.com.walletapp.contract.TransferDestination
import br.com.walletapp.contract.TransferReceipt
import br.com.walletapp.contract.TransferRequest
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.ui.Format
import br.com.walletapp.desktop.ui.auth.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The three steps of every payment (ADR-001, decision 6): fill in, confirm, receipt. Sealed, so the
 * screen draws each step and nothing else.
 *
 * The Idempotency-Key is created when the customer reaches the confirmation and kept while they stay
 * there: clicking "Confirmar" again after a timeout repeats the same payment instead of making a new one.
 */
sealed interface Step<out P, out R> {
    data object Form : Step<Nothing, Nothing>
    data class Confirm<P>(val preview: P, val idempotencyKey: String = UUID.randomUUID().toString()) : Step<P, Nothing>
    data class Done<R>(val receipt: R) : Step<Nothing, R>
}

class TransferViewModel(private val api: AppApiClient, private val scope: CoroutineScope) {

    data class Form(val branch: String = "", val number: String = "", val checkDigit: String = "", val amount: String = "",
                    val description: String = "")

    /** What the confirmation shows: who receives (from app-api) and how much. */
    data class Preview(val destination: TransferDestination, val amountCents: Long)

    data class State(val form: Form = Form(), val step: Step<Preview, TransferReceipt> = Step.Form,
                     val busy: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), error = null) }

    /** Checks the amount here, the destination at app-api, and shows the confirmation. Nothing moves yet. */
    fun review(): Job? {
        val f = _state.value.form
        val cents = Format.parseCents(f.amount) ?: run {
            _state.update { it.copy(error = "Informe um valor válido, como 150,00.") }
            return null
        }
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val destination = api.transferDestination(f.branch.trim(), f.number.trim(), f.checkDigit.trim())
                _state.update { it.copy(busy = false, step = Step.Confirm(Preview(destination, cents))) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = message(e)) }
            }
        }
    }

    fun confirm(): Job? {
        val s = _state.value
        val confirm = s.step as? Step.Confirm ?: return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val d = confirm.preview.destination
                val receipt = api.transfer(
                    TransferRequest(d.branch, d.number, d.checkDigit, confirm.preview.amountCents,
                        s.form.description.trim().ifEmpty { null }),
                    confirm.idempotencyKey,
                )
                _state.update { it.copy(busy = false, step = Step.Done(receipt)) }
            } catch (e: Exception) {
                // Stays on the confirmation with the same key: "Confirmar" again is a retry, not a second transfer.
                _state.update { it.copy(busy = false, error = message(e)) }
            }
        }
    }

    /** Back to the form to change something: the next confirmation gets a new key. */
    fun change() = _state.update { it.copy(step = Step.Form, error = null) }

    fun reset() {
        _state.value = State()
    }
}

class PixViewModel(
    private val api: AppApiClient,
    private val scope: CoroutineScope,
    private val pollEveryMillis: Long = 1_000,
    private val pollTimes: Int = 10,
) {

    data class Form(val name: String = "", val taxId: String = "", val ispb: String = "", val branch: String = "",
                    val account: String = "", val amount: String = "", val description: String = "")

    data class Preview(val payee: PixPayee, val amountCents: Long)

    data class State(val form: Form = Form(), val step: Step<Preview, PixReceipt> = Step.Form,
                     val busy: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), error = null) }

    /** A Pix goes to another institution: there is nobody to look up here, the confirmation repeats what was typed. */
    fun review() {
        val f = _state.value.form
        val cents = Format.parseCents(f.amount)
        val error = when {
            listOf(f.name, f.taxId, f.ispb, f.branch, f.account).any(String::isBlank) -> "Preencha os dados do recebedor."
            !Format.isTaxId(f.taxId) -> Format.TAX_ID_ERROR
            cents == null -> "Informe um valor válido, como 150,00."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        val payee = PixPayee(f.ispb.trim(), f.branch.trim(), f.account.trim(), f.taxId.trim(), f.name.trim())
        _state.update { it.copy(step = Step.Confirm(Preview(payee, cents!!)), error = null) }
    }

    /**
     * Sends, shows the receipt, and follows the Pix for a few seconds: wallet-pix settles it right after
     * answering, so the receipt usually turns from "enviado" into "concluído" (or "não concluído") by itself.
     */
    fun confirm(): Job? {
        val s = _state.value
        val confirm = s.step as? Step.Confirm ?: return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            val sent = try {
                api.sendPix(PixRequest(confirm.preview.payee, confirm.preview.amountCents,
                    s.form.description.trim().ifEmpty { null }), confirm.idempotencyKey)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = message(e)) }
                return@launch
            }
            _state.update { it.copy(busy = false, step = Step.Done(sent)) }
            var receipt = sent
            repeat(pollTimes) {
                if (receipt.status != "SENT") return@launch
                delay(pollEveryMillis)
                receipt = runCatching { api.pixStatus(sent.endToEndId) }.getOrDefault(receipt)
                _state.update { it.copy(step = Step.Done(receipt)) }
            }
        }
    }

    fun change() = _state.update { it.copy(step = Step.Form, error = null) }

    fun reset() {
        _state.value = State()
    }
}

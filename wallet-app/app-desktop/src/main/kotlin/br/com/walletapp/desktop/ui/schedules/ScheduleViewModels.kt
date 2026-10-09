package br.com.walletapp.desktop.ui.schedules

import br.com.walletapp.contract.PixPayee
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleRequest
import br.com.walletapp.contract.TransferTarget
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.ui.Format
import br.com.walletapp.desktop.ui.auth.message
import br.com.walletapp.desktop.ui.payments.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The customer's schedules, the one whose detail is open, and cancelling it. */
class SchedulesViewModel(private val api: AppApiClient, private val scope: CoroutineScope) {

    data class State(
        val schedules: List<Schedule> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val selected: Schedule? = null,
        val cancelling: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(): Job {
        _state.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try {
                val list = api.schedules()
                _state.update { it.copy(schedules = list, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = message(e)) }
            }
        }
    }

    /** The detail shows what the list already brought: no new request. */
    fun open(s: Schedule) = _state.update { it.copy(selected = s) }

    fun close() = _state.update { it.copy(selected = null) }

    /** Cancels and shows the result in the open detail; the list is refreshed with it. */
    fun cancel(): Job? {
        val selected = _state.value.selected ?: return null
        _state.update { it.copy(cancelling = true, error = null) }
        return scope.launch {
            try {
                val cancelled = api.cancelSchedule(selected.id)
                _state.update { s ->
                    s.copy(cancelling = false, selected = cancelled,
                        schedules = s.schedules.map { if (it.id == cancelled.id) cancelled else it })
                }
            } catch (e: Exception) {
                _state.update { it.copy(cancelling = false, error = message(e)) }
            }
        }
    }
}

/** A new schedule: a transfer or a Pix, a date, then confirm. Same three steps as the payments. */
class NewScheduleViewModel(private val api: AppApiClient, private val scope: CoroutineScope) {

    data class Form(
        val pix: Boolean = false,
        val date: String = "",
        val amount: String = "",
        val description: String = "",
        // transfer
        val branch: String = "",
        val number: String = "",
        val checkDigit: String = "",
        // Pix
        val name: String = "",
        val taxId: String = "",
        val ispb: String = "",
        val pixBranch: String = "",
        val account: String = "",
    )

    data class State(val form: Form = Form(), val step: Step<ScheduleRequest, Schedule> = Step.Form,
                     val busy: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun edit(change: (Form) -> Form) = _state.update { it.copy(form = change(it.form), error = null) }

    /** Checks the format here; whether the date and the destination are acceptable is app-api's (and the scheduler's) to say. */
    fun review() {
        val f = _state.value.form
        val date = Format.parseDate(f.date)
        val cents = Format.parseCents(f.amount)
        val error = when {
            date == null -> "Informe a data no formato dd/mm/aaaa."
            cents == null -> "Informe um valor válido, como 150,00."
            !f.pix && listOf(f.branch, f.number, f.checkDigit).any(String::isBlank) -> "Informe a conta de destino."
            f.pix && listOf(f.name, f.taxId, f.ispb, f.pixBranch, f.account).any(String::isBlank) -> "Preencha os dados do recebedor."
            f.pix && !Format.isTaxId(f.taxId) -> Format.TAX_ID_ERROR
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        val request = ScheduleRequest(
            type = if (f.pix) "PIX" else "TRANSFER",
            executeOn = date!!,
            amountCents = cents!!,
            description = f.description.trim().ifEmpty { null },
            transfer = if (f.pix) null else TransferTarget(f.branch.trim(), f.number.trim(), f.checkDigit.trim()),
            pix = if (f.pix) PixPayee(f.ispb.trim(), f.pixBranch.trim(), f.account.trim(), f.taxId.trim(), f.name.trim()) else null,
        )
        _state.update { it.copy(step = Step.Confirm(request)) }
    }

    fun confirm(): Job? {
        val confirm = _state.value.step as? Step.Confirm ?: return null
        _state.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val created = api.createSchedule(confirm.preview, confirm.idempotencyKey)
                _state.update { it.copy(busy = false, step = Step.Done(created)) }
            } catch (e: Exception) {
                // Same key on the next "Confirmar": a retry, never a second schedule.
                _state.update { it.copy(busy = false, error = message(e)) }
            }
        }
    }

    fun change() = _state.update { it.copy(step = Step.Form, error = null) }

    fun reset() {
        _state.value = State()
    }
}

package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.LoginRepository
import br.com.walletapp.api.application.port.ScheduleOutcome
import br.com.walletapp.api.application.port.ScheduleView
import br.com.walletapp.api.application.port.SchedulerGateway
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.NotFoundException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleRequest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * The logged-in customer's schedules (ADR-001, step 4). wallet-scheduler only separates tenants, so this
 * service is what separates customers: every schedule read or cancelled must be paid by the token's account.
 */
class ScheduleService(
    private val scheduler: SchedulerGateway,
    private val logins: LoginRepository,
    private val clock: Clock,
) {

    fun list(payer: AccountId): List<Schedule> = scheduler.list(payer).filter { it.payer == payer }.map(::shown)

    fun get(payer: AccountId, id: String): Schedule = shown(mine(payer, id))

    fun create(login: LoginId, payer: AccountId, r: ScheduleRequest, idempotencyKey: String?): Schedule {
        val key = IdempotencyKeys.forCustomer(login, idempotencyKey)
        validate(r)
        val cpf = logins.findById(login)?.cpf ?: throw IllegalStateException("token of a login that does not exist")
        return when (val o = scheduler.create(payer, cpf, r, key)) {
            is ScheduleOutcome.Done -> shown(o.view)
            is ScheduleOutcome.Refused -> throw BusinessRuleException(o.code, CustomerMessages.of(o.code))
        }
    }

    /** Only the customer's own schedule: someone else's id is simply not found. The deadline is wallet-scheduler's. */
    fun cancel(payer: AccountId, id: String): Schedule {
        mine(payer, id)
        return when (val o = scheduler.cancel(id)) {
            is ScheduleOutcome.Done -> shown(o.view)
            is ScheduleOutcome.Refused -> throw BusinessRuleException(o.code, CustomerMessages.of(o.code))
        }
    }

    private fun mine(payer: AccountId, id: String): ScheduleView =
        scheduler.get(id)?.takeIf { it.payer == payer }
            ?: throw NotFoundException("SCHEDULE_NOT_FOUND", CustomerMessages.of("SCHEDULE_NOT_FOUND"))

    /** Until the day before, in Brasília - the same rule wallet-scheduler enforces, so the button never lies. */
    private fun shown(v: ScheduleView): Schedule {
        val s = v.schedule
        val today = LocalDate.now(clock.withZone(BRASILIA))
        val canCancel = s.status == "ACTIVE" && s.execution.status == "PENDING" && LocalDate.parse(s.executeOn).isAfter(today)
        return s.copy(canCancel = canCancel)
    }

    private fun validate(r: ScheduleRequest) {
        when (r.type) {
            "TRANSFER" -> if (r.transfer == null || r.pix != null) invalid("Informe a conta de destino.")
            "PIX" -> if (r.pix == null || r.transfer != null) invalid("Informe os dados do recebedor do Pix.")
            else -> invalid("Tipo de agendamento inválido.")
        }
        if (r.amountCents <= 0) invalid("Informe um valor maior que zero.")
        if (runCatching { LocalDate.parse(r.executeOn) }.isFailure) invalid("Data inválida.")
        if ((r.description?.trim()?.length ?: 0) > 140) invalid("A descrição pode ter até 140 caracteres.")
    }

    private fun invalid(message: String): Nothing = throw ValidationException("INVALID_SCHEDULE", message)

    private companion object {
        val BRASILIA: ZoneId = ZoneId.of("America/Sao_Paulo")
    }
}

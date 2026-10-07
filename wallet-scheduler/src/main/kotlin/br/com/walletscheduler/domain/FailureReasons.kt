package br.com.walletscheduler.domain

/**
 * Why an attempt did not go through, as the customer reads it (ADR-001, decision 7).
 *
 * @property retryToday whether the next window of the same day may succeed (balance can arrive,
 *                      a temporary block can be lifted); false means the execution fails right away
 */
data class FailureReason(val code: String, val message: String, val retryToday: Boolean)

object FailureReasons {

    /** Not a refusal by wallet-core: the day ended with the payment never tried (e.g. service down all day). */
    const val MISSED_DAY = "MISSED_DAY"

    private val known = listOf(
        FailureReason("INSUFFICIENT_FUNDS", "Saldo insuficiente no momento do pagamento", retryToday = true),
        FailureReason("ACCOUNT_NOT_ACTIVE", "Conta pagadora bloqueada ou inativa", retryToday = true),
        FailureReason("DESTINATION_ACCOUNT_NOT_FOUND", "Conta de destino não encontrada", retryToday = false),
        FailureReason("ACCOUNT_NOT_FOUND", "Conta não encontrada", retryToday = false),
        FailureReason("SAME_ACCOUNT", "Conta de destino igual à conta pagadora", retryToday = false),
        FailureReason(MISSED_DAY, "O pagamento não pôde ser executado na data agendada", retryToday = false),
        // Pix: refusals of the Pix service before sending (it checks the payer and its own policies)...
        FailureReason("POLICY_MAX_AMOUNT", "Valor acima do limite por Pix", retryToday = false),
        FailureReason("PAYER_TAX_ID_MISMATCH", "CPF/CNPJ do pagador não confere com a conta", retryToday = false),
        FailureReason("PAYER_ACCOUNT_BLOCKED", "Conta pagadora bloqueada", retryToday = true),
        FailureReason("PAYER_ACCOUNT_CLOSED", "Conta pagadora encerrada", retryToday = false),
        FailureReason("PAYER_ACCOUNT_NOT_FOUND", "Conta pagadora não encontrada", retryToday = false),
        // ... and rejections by the SPI or the payee's institution after sending (the debit is reversed).
        FailureReason("AC03", "Conta do recebedor inexistente ou inválida", retryToday = false),
        FailureReason("AC06", "Conta do recebedor bloqueada", retryToday = false),
        FailureReason("AC07", "Conta do recebedor encerrada", retryToday = false),
        FailureReason("AC14", "Tipo de conta do recebedor incorreto", retryToday = false),
        FailureReason("BE01", "CPF/CNPJ não corresponde ao titular da conta do recebedor", retryToday = false),
    ).associateBy { it.code }

    /** An unknown code fails right away: retrying something we do not understand could repeat a harm. */
    fun of(code: String): FailureReason =
        known[code] ?: FailureReason(code, "Não foi possível concluir o pagamento ($code)", retryToday = false)
}

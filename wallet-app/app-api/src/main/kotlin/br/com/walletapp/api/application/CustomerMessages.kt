package br.com.walletapp.api.application

/**
 * What the customer reads for each code wallet-core, wallet-pix or the SPI answer (ADR-001, decision 6).
 * One place, so the same refusal always reads the same way, whichever screen it comes from.
 */
object CustomerMessages {

    private val messages = mapOf(
        "INSUFFICIENT_FUNDS" to "Saldo insuficiente.",
        "ACCOUNT_NOT_ACTIVE" to "Sua conta não pode movimentar no momento. Procure o atendimento.",
        "DESTINATION_ACCOUNT_NOT_FOUND" to "Conta de destino não encontrada.",
        "ACCOUNT_NOT_FOUND" to "Conta não encontrada.",
        "SAME_ACCOUNT" to "A conta de destino é a sua própria conta.",
        "POLICY_MAX_AMOUNT" to "Valor acima do limite por Pix.",
        "INVALID_PAYEE" to "Dados do recebedor inválidos. Confira ISPB, agência, conta e CPF/CNPJ.",
        "VALIDATION_FAILED" to "Dados do recebedor inválidos. Confira ISPB, agência, conta e CPF/CNPJ.",
        "INVALID_PIX_COUNTERPARTY" to "Dados do recebedor inválidos. Confira ISPB, agência, conta e CPF/CNPJ.",
        "PAYER_TAX_ID_MISMATCH" to "Não foi possível confirmar o titular da sua conta. Procure o atendimento.",
        "PAYER_ACCOUNT_BLOCKED" to "Sua conta está bloqueada.",
        "PAYER_ACCOUNT_CLOSED" to "Sua conta está encerrada.",
        // Schedules (wallet-scheduler).
        "INVALID_EXECUTION_DATE" to "Escolha uma data a partir de amanhã, em até um ano.",
        "PAYER_ACCOUNT_NOT_ACTIVE" to "Sua conta não pode movimentar no momento. Procure o atendimento.",
        "CANCELLATION_DEADLINE_PASSED" to "Este agendamento só podia ser cancelado até a véspera.",
        "SCHEDULE_NOT_ACTIVE" to "Este agendamento não está mais ativo.",
        "SCHEDULE_NOT_FOUND" to "Agendamento não encontrado.",
        "INVALID_REQUEST" to "Dados do agendamento inválidos.",
        "TENANT_NOT_CONFIGURED" to "Agendamentos indisponíveis no momento. Procure o atendimento.",
        // Rejections after sending (the money came back).
        "AC03" to "Conta do recebedor inexistente ou inválida.",
        "AC06" to "Conta do recebedor bloqueada.",
        "AC07" to "Conta do recebedor encerrada.",
        "AC14" to "Tipo de conta do recebedor incorreto.",
        "BE01" to "CPF/CNPJ não corresponde ao titular da conta do recebedor.",
    )

    fun of(code: String): String = known(code) ?: "Não foi possível concluir a operação ($code)."

    /** The message for [code], or null when this map does not know it (the caller keeps the service's own). */
    fun known(code: String): String? = messages[code]
}

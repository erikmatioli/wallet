package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response contracts of the public API. Amounts are decimal BRL values with 2 places. */
public final class ApiModels {

    private ApiModels() {
    }

    // ---------------------------------------------------------------- requests

    @Schema(description = "Payload para o onboarding de um novo cliente e abertura de conta.")
    public record OnboardCustomerRequest(
            @Schema(description = "Nome completo do cliente", example = "Maria da Silva", maxLength = 140)
            @NotBlank @Size(max = 140) String name,

            @Schema(description = "Documento de identificação (CPF ou CNPJ)", example = "12345678901", maxLength = 32)
            @NotBlank @Size(max = 32) String taxId,

            @Schema(description = "Referência externa opcional do sistema cliente", example = "ext-ref-987", maxLength = 64)
            @Size(max = 64) String externalRef) {
    }

    @Schema(description = "Payload para movimentação financeira (depósito ou saque).")
    public record MoneyMovementRequest(
            @Schema(description = "Valor da transação em Reais (BRL) com duas casas decimais", example = "150.50", minimum = "0.01")
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

            @Schema(description = "Descrição ou motivo da movimentação", example = "Pagamento de fatura", maxLength = 140)
            @Size(max = 140) String description) {
    }

    @Schema(description = "Dados detalhados da agência e número da conta de destino.")
    public record DestinationNumber(
            @Schema(description = "Número da agência (exatamente 4 dígitos)", example = "0001", pattern = "\\d{4}")
            @NotBlank @Pattern(regexp = "\\d{4}") String branch,

            @Schema(description = "Número da conta (até 20 dígitos)", example = "1234567", pattern = "\\d{1,20}")
            @NotBlank @Pattern(regexp = "\\d{1,20}") String number,

            @Schema(description = "Dígito verificador da conta", example = "5", pattern = "\\d")
            @NotBlank @Pattern(regexp = "\\d") String checkDigit) {
    }

    @Schema(description = "Payload para transferência entre contas. É obrigatório informar exatamente um dos destinos: destinationAccountId ou destination.")
    public record TransferRequest(
            @Schema(description = "UUID da conta de origem", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
            @NotNull UUID sourceAccountId,

            @Schema(description = "UUID da conta de destino interna (se houver)", example = "b1ffcd88-8d9a-3de7-aa5c-5aa8ac279f00", nullable = true)
            UUID destinationAccountId,

            @Valid
            @Schema(description = "Dados da conta de destino externa (caso não seja uma conta interna)")
            DestinationNumber destination,

            @Schema(description = "Valor da transferência em Reais (BRL)", example = "250.00", minimum = "0.01")
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

            @Schema(description = "Descrição da transferência", example = "Pix para fornecedor", maxLength = 140)
            @Size(max = 140) String description) {
    }

    // ---------------------------------------------------------------- responses

    @Schema(description = "Representação dos dados cadastrais e bancários de uma conta.")
    public record AccountResponse(
            @Schema(description = "UUID único da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID id,
            @Schema(description = "ISPB da instituição", example = "00000000") String ispb,
            @Schema(description = "Número da agência", example = "0001") String branch,
            @Schema(description = "Número da conta", example = "123456") String number,
            @Schema(description = "Dígito verificador", example = "7") String checkDigit,
            @Schema(description = "Número formatado da conta", example = "0001 / 123456-7") String formatted,
            @Schema(description = "Tipo da conta no padrão BCB", example = "CACC") String type,
            @Schema(description = "Status atual da conta", example = "ACTIVE") String status,
            @Schema(description = "Saldo atual da conta", example = "1250.00") BigDecimal balance,
            @Schema(description = "Moeda padrão", example = "BRL") String currency) {

        static AccountResponse from(Account a) {
            return new AccountResponse(
                    a.id().value(),
                    a.number().ispb(),
                    a.number().branch(),
                    a.number().number(),
                    a.number().checkDigit(),
                    a.number().formatted(),
                    a.number().type().bcbCode(),
                    a.status().name(),
                    a.balance().toDecimal(),
                    Money.CURRENCY);
        }
    }

    @Schema(description = "Dados do cliente cadastrado.")
    public record CustomerResponse(
            @Schema(description = "UUID do cliente") UUID id,
            @Schema(description = "Nome do cliente") String name,
            @Schema(description = "Tipo de documento") String documentType,
            @Schema(description = "Referência externa") String externalRef,
            @Schema(description = "Status do cliente") String status) {
    }

    @Schema(description = "Resposta consolidada da operação de Onboard (Cliente + Conta).")
    public record OnboardCustomerResponse(CustomerResponse customer, AccountResponse account) {
    }

    @Schema(description = "Detalhes da transação financeira executada.")
    public record TransactionResponse(
            @Schema(description = "UUID da transação") UUID id,
            @Schema(description = "Tipo da transação (ex: DEPOSIT, WITHDRAWAL)") String type,
            @Schema(description = "Valor movimentado") BigDecimal amount,
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Descrição") String description,
            @Schema(description = "Timestamp UTC de ocorrência") Instant occurredAt,
            @Schema(description = "Indica se a resposta veio de um replay de idempotência") boolean replayed) {
    }

    @Schema(description = "Lançamento individual no extrato (Ledger Entry).")
    public record EntryResponse(
            @Schema(description = "UUID da transação geradora") UUID transactionId,
            @Schema(description = "Número sequencial do lançamento") long sequence,
            @Schema(description = "Tipo do lançamento") String type,
            @Schema(description = "Direção do fluxo (DEBIT ou CREDIT)") String direction,
            @Schema(description = "Valor do lançamento") BigDecimal amount,
            @Schema(description = "Saldo resultante após o lançamento") BigDecimal balanceAfter,
            @Schema(description = "Descrição") String description,
            @Schema(description = "Timestamp do registro") Instant occurredAt) {

        static EntryResponse from(LedgerEntry e) {
            return new EntryResponse(e.transactionId().value(), e.sequence(), e.type().name(), e.direction().name(),
                    e.amount().toDecimal(), e.balanceAfter().toDecimal(), e.description(), e.occurredAt());
        }
    }

    @Schema(description = "Extrato detalhado contendo a lista de entradas e o cursor para paginação.")
    public record StatementResponse(
            @Schema(description = "Lista de entradas do ledger") List<EntryResponse> entries,
            @Schema(description = "Cursor para buscar as próximas páginas (null se não houver mais registros)") Long nextBefore) {
    }

    @Schema(description = "Consulta de saldo atual da conta.")
    public record BalanceResponse(
            @Schema(description = "UUID da conta") UUID accountId,
            @Schema(description = "Saldo atual") BigDecimal balance,
            @Schema(description = "Moeda", example = "BRL") String currency) {
    }

    @Schema(description = "Relatório de auditoria de integridade do ledger da conta.")
    public record AuditResponse(
            @Schema(description = "UUID da conta auditada") UUID accountId,
            @Schema(description = "Total de entradas processadas") long entryCount,
            @Schema(description = "Saldo atualmente armazenado no registro principal") BigDecimal storedBalance,
            @Schema(description = "Saldo reconstruído a partir dos eventos do ledger") BigDecimal replayedBalance,
            @Schema(description = "Versão atual da entidade") long version,
            @Schema(description = "Indica se o saldo armazenado bate perfeitamente com o reprocessado") boolean consistent,
            @Schema(description = "Lista de eventuais achados ou inconsistências encontradas") List<String> findings) {
    }
}
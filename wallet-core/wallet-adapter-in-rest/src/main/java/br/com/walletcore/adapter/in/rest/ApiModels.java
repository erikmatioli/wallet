package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.application.port.in.ListAccountsUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
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
    @Schema(description = "Dados para cadastro de cliente e abertura automática de conta.")
    public record OnboardCustomerRequest(
            @Schema(description = "Nome completo do cliente", example = "João da Silva", maxLength = 140)
            @NotBlank @Size(max = 140) String name,

            @Schema(description = "Número do documento fiscal (CPF ou CNPJ)", example = "36686290387", maxLength = 32)
            @NotBlank @Size(max = 32) String taxId,

            @Schema(description = "Referência externa opcional para controle do cliente", example = "ref-ext-12345", maxLength = 64)
            @Size(max = 64) String externalRef,

            @Schema(description = "E-mail opcional do cliente: para onde os produtos dele (o app) mandam códigos de acesso", example = "maria@example.com", maxLength = 254)
            @Size(max = 254) String email) {
    }

    @Schema(description = "Novo e-mail do cliente; vazio ou nulo remove o e-mail.")
    public record ChangeEmailRequest(
            @Schema(description = "E-mail", example = "maria@example.com", maxLength = 254) @Size(max = 254) String email) {
    }

    @Schema(description = "Contato do cliente, como o operador cadastrou: para onde os produtos dele podem mandar códigos.")
    public record CustomerContactResponse(
            @Schema(description = "UUID do cliente") UUID customerId,
            @Schema(description = "Nome do cliente", example = "Maria Oliveira") String name,
            @Schema(description = "E-mail; ausente quando não há") String email,
            @Schema(description = "Status do cadastro", example = "ACTIVE") String status,
            @Schema(description = "UUID da conta de pagamento") UUID accountId) {
    }

    @Schema(description = "Dados para movimentação financeira (depósito ou saque).")
    public record MoneyMovementRequest(
            @Schema(description = "Valor da transação (deve ser maior que zero)", example = "150.00", minimum = "0.01")
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

            @Schema(description = "Descrição detalhada da movimentação", example = "Depósito via Pix / Caixa Eletrônico", maxLength = 140)
            @Size(max = 140) String description) {
    }

    @Schema(description = "Composição dos dados da conta bancária de destino.")
    public record DestinationNumber(
            @Schema(description = "Número da agência bancária (4 dígitos)", example = "0001")
            @NotBlank @Pattern(regexp = "\\d{4}") String branch,

            @Schema(description = "Número da conta corrente ou de pagamento", example = "1234567")
            @NotBlank @Pattern(regexp = "\\d{1,20}") String number,

            @Schema(description = "Dígito verificador da conta", example = "5")
            @NotBlank @Pattern(regexp = "\\d") String checkDigit) {
    }

    @Schema(description = "Payload para realização de transferência entre contas. É obrigatório informar exatamente um dos destinos: destinationAccountId ou destination.")
    public record TransferRequest(
            @Schema(description = "UUID da conta de origem", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
            @NotNull UUID sourceAccountId,

            @Schema(description = "UUID da conta de destino (quando interna)", example = "b1ffdc88-8d0a-3de7-aa5c-5aa8ac270b22")
            UUID destinationAccountId,

            @Schema(description = "Dados detalhados do número de destino (quando externa ou por agência/conta)")
            @Valid DestinationNumber destination,

            @Schema(description = "Valor monetário a ser transferido", example = "250.50", minimum = "0.01")
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,

            @Schema(description = "Motivo ou descrição da transferência", example = "Pagamento de serviços prestados", maxLength = 140)
            @Size(max = 140) String description) {
    }

    // ---------------------------------------------------------------- responses
    @Schema(description = "Detalhes estruturados de uma conta de pagamento.")
    public record AccountResponse(
            @Schema(description = "Identificador único (UUID) da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID id,
            @Schema(description = "Código ISPB da instituição", example = "00000000") String ispb,
            @Schema(description = "Agência da conta", example = "0001") String branch,
            @Schema(description = "Número da conta", example = "1234567") String number,
            @Schema(description = "Dígito verificador", example = "5") String checkDigit,
            @Schema(description = "Representação formatada da conta", example = "0001 / 1234567-5") String formatted,
            @Schema(description = "Tipo da conta perante o arranjo/BACEN", example = "TRAN") String type,
            @Schema(description = "Status atual da conta", example = "ACTIVE") String status,
            @Schema(description = "Saldo atual disponível", example = "1250.00") BigDecimal balance,
            @Schema(description = "Moeda oficial da conta", example = "BRL") String currency) {

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

    @Schema(description = "Informações do cliente associado.")
    public record CustomerResponse(
            @Schema(description = "UUID do cliente", example = "c2ffdc99-7c0b-3ef7-cc6d-4bb9bd380f33") UUID id,
            @Schema(description = "Nome do cliente", example = "Maria Oliveira") String name,
            @Schema(description = "Tipo de documento", example = "CPF") String documentType,
            @Schema(description = "Referência externa", example = "ext-ref-99") String externalRef,
            @Schema(description = "Status do cadastro", example = "ACTIVE") String status,
            @Schema(description = "E-mail do cliente; ausente quando não há", example = "maria@example.com") String email) {
    }

    @Schema(description = "Detalhes completos da conta incluindo o nome e documento mascarado do titular.")
    public record AccountDetailResponse(
            @Schema(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID id,
            @Schema(description = "ISPB", example = "00000000") String ispb,
            @Schema(description = "Agência", example = "0001") String branch,
            @Schema(description = "Número", example = "1234567") String number,
            @Schema(description = "Dígito verificador", example = "5") String checkDigit,
            @Schema(description = "Conta formatada", example = "0001 / 1234567-5") String formatted,
            @Schema(description = "Tipo", example = "TRAN") String type,
            @Schema(description = "Status", example = "ACTIVE") String status,
            @Schema(description = "Saldo", example = "1250.00") BigDecimal balance,
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Nome do titular da conta", example = "João da Silva") String customerName,
            @Schema(description = "Documento mascarado do titular", example = "***.686.290-**") String documentMasked,
            @Schema(description = "UUID do cliente titular") UUID customerId,
            @Schema(description = "E-mail do titular; ausente quando não há", example = "maria@example.com") String customerEmail) {

        static AccountDetailResponse from(QueryAccountUseCase.AccountDetail d) {
            Account a = d.account();
            return new AccountDetailResponse(
                    a.id().value(), a.number().ispb(), a.number().branch(), a.number().number(),
                    a.number().checkDigit(), a.number().formatted(), a.number().type().bcbCode(), a.status().name(),
                    a.balance().toDecimal(), Money.CURRENCY, d.customerName(), d.documentMasked(),
                    a.customerId() == null ? null : a.customerId().value(), d.customerEmail());
        }
    }

    @Schema(description = "Resposta combinada do processo de onboarding (Cliente + Conta aberta).")
    public record OnboardCustomerResponse(CustomerResponse customer, AccountResponse account) {
    }

    @Schema(description = "Detalhes do recibo da transação financeira executada.")
    public record TransactionResponse(
            @Schema(description = "UUID da transação gerada", example = "d3ffdc88-5c0a-2ef6-bb6d-3bb9bd380f44") UUID id,
            @Schema(description = "Tipo da transação", example = "DEPOSIT") String type,
            @Schema(description = "Valor movimentado", example = "500.00") BigDecimal amount,
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Descrição informada", example = "Aporte inicial") String description,
            @Schema(description = "Momento em que ocorreu (ISO-8601)", example = "2026-10-02T12:00:00Z") Instant occurredAt,
            @Schema(description = "Indica se a resposta foi obtida por reprocessamento de idempotência (Replay)", example = "false") boolean replayed,
            @Schema(description = "EndToEndId gravado (apenas tipos PIX_*). Num replay de PIX_OUT é o da primeira tentativa", example = "E12345678202610061200abcDEF12345") String endToEndId) {
    }

    @Schema(description = "Entrada detalhada do extrato / ledger contábil com dados de contraparte.")
    public record EntryResponse(
            @Schema(description = "UUID da transação associada", example = "d3ffdc88-5c0a-2ef6-bb6d-3bb9bd380f44") UUID transactionId,
            @Schema(description = "Número sequencial da linha no ledger", example = "1") long sequence,
            @Schema(description = "Tipo da transação", example = "TRANSFER") String type,
            @Schema(description = "Direção do movimento (DEBIT ou CREDIT)", example = "CREDIT") String direction,
            @Schema(description = "Valor da entrada", example = "100.00") BigDecimal amount,
            @Schema(description = "Saldo da conta após esta entrada", example = "1350.00") BigDecimal balanceAfter,
            @Schema(description = "Descrição", example = "Transferência recebida") String description,
            @Schema(description = "Data e hora do lançamento", example = "2026-10-02T12:30:00Z") Instant occurredAt,
            @Schema(description = "UUID da conta contraparte (apenas para transferências)", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID counterpartyAccountId,
            @Schema(description = "Nome do cliente contraparte (quando aplicável)", example = "Maria Oliveira") String counterpartyCustomerName,
            @Schema(description = "Conta formatada da contraparte (quando aplicável)", example = "0001 / 7654321-0") String counterpartyAccountFormatted,
            @Schema(description = "Detalhe do Pix (apenas para os tipos PIX_*)") PixDetailResponse pix) {

        static EntryResponse from(QueryAccountUseCase.StatementEntry se) {
            LedgerEntry e = se.entry();
            UUID counterparty = e.type() == TransactionType.TRANSFER && e.counterpartyAccountId() != null
                    ? e.counterpartyAccountId().value() : null;
            return new EntryResponse(e.transactionId().value(), e.sequence(), e.type().name(), e.direction().name(),
                    e.amount().toDecimal(), e.balanceAfter().toDecimal(), e.description(), e.occurredAt(), counterparty,
                    se.counterpartyCustomerName(), se.counterpartyAccountFormatted(),
                    se.pix() == null ? null : PixDetailResponse.from(se.pix()));
        }
    }

    @Schema(description = "Detalhe de uma transação Pix, gravado no momento do lançamento.")
    public record PixDetailResponse(
            @Schema(description = "EndToEndId do Pix", example = "E12345678202610061200abcDEF12345") String endToEndId,
            @Schema(description = "Identificador da devolução (apenas PIX_RETURN_*)", example = "D99999999202610061200abcDEF12345") String returnId,
            @Schema(description = "Transação Pix original (estorno e devoluções)") UUID relatedTransactionId,
            @Schema(description = "Contraparte do Pix") PixCounterpartyResponse counterparty,
            @Schema(description = "Motivo do estorno ou da devolução", example = "MD06") String reasonCode,
            @Schema(description = "Mensagem do pagador", example = "Aluguel de outubro") String remittanceInfo) {

        static PixDetailResponse from(PixDetail d) {
            PixCounterparty c = d.counterparty();
            return new PixDetailResponse(d.endToEndId(), d.returnId(),
                    d.relatedTransactionId() == null ? null : d.relatedTransactionId().value(),
                    new PixCounterpartyResponse(c.name(), c.taxIdMasked(), c.ispb(), c.branch(), c.account(),
                            c.accountType()),
                    d.reasonCode(), d.remittanceInfo());
        }
    }

    @Schema(description = "Contraparte de um Pix: o recebedor de um Pix enviado, o pagador de um Pix recebido.")
    public record PixCounterpartyResponse(
            @Schema(description = "Nome", example = "Maria Oliveira") String name,
            @Schema(description = "CPF/CNPJ mascarado", example = "***7735") String taxIdMasked,
            @Schema(description = "ISPB da instituição", example = "99999999") String ispb,
            @Schema(description = "Agência", example = "0001") String branch,
            @Schema(description = "Conta, com dígito", example = "12345678") String account,
            @Schema(description = "Tipo de conta no SPI", example = "TRAN") String accountType) {
    }

    @Schema(description = "Contraparte informada num lançamento Pix. O CPF/CNPJ é validado e guardado só mascarado.")
    public record PixCounterpartyRequest(
            @Schema(description = "Nome", example = "Maria Oliveira", maxLength = 140)
            @NotBlank @Size(max = 140) String name,
            @Schema(description = "CPF/CNPJ completo", example = "11144477735")
            @NotBlank @Size(max = 32) String taxId,
            @Schema(description = "ISPB da instituição", example = "99999999")
            @NotBlank @Pattern(regexp = "\\d{8}") String ispb,
            @Schema(description = "Agência (opcional)", example = "0001")
            @Pattern(regexp = "\\d{4}") String branch,
            @Schema(description = "Conta, com dígito", example = "12345678")
            @NotBlank @Pattern(regexp = "\\d{1,21}") String account,
            @Schema(description = "Tipo de conta no SPI (opcional)", example = "TRAN")
            @Pattern(regexp = "[A-Z]{4}") String accountType) {
    }

    @Schema(description = "Lançamento Pix (ADR-010). O tipo define a direção: créditos em /pix-credits, débitos em /pix-debits.")
    public record PixTransactionRequest(
            @Schema(description = "Tipo do lançamento", example = "PIX_IN",
                    allowableValues = {"PIX_IN", "PIX_OUT", "PIX_RETURN_IN", "PIX_RETURN_OUT"})
            @NotNull TransactionType type,
            @Schema(description = "Valor", example = "150.00", minimum = "0.01")
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @Schema(description = "Descrição exibida no extrato", example = "Pix recebido de Maria Oliveira", maxLength = 140)
            @Size(max = 140) String description,
            @Schema(description = "EndToEndId do Pix (numa devolução, o do Pix original)", example = "E12345678202610061200abcDEF12345")
            @NotBlank String endToEndId,
            @Schema(description = "Identificador da devolução (obrigatório em PIX_RETURN_*)", example = "D99999999202610061200abcDEF12345")
            String returnId,
            @Schema(description = "Transação Pix original (obrigatória em PIX_RETURN_*)")
            UUID relatedTransactionId,
            @Schema(description = "Contraparte do Pix")
            @NotNull @Valid PixCounterpartyRequest counterparty,
            @Schema(description = "Motivo da devolução (obrigatório em PIX_RETURN_*)", example = "MD06")
            String reasonCode,
            @Schema(description = "Mensagem do pagador", example = "Aluguel de outubro", maxLength = 140)
            @Size(max = 140) String remittanceInfo) {
    }

    @Schema(description = "Lista paginada de lançamentos de extrato.")
    public record StatementResponse(
            @Schema(description = "Lista de entradas do ledger") List<EntryResponse> entries,
            @Schema(description = "Cursor para buscar a próxima página", example = "12") Long nextBefore) {
    }

    @Schema(description = "Consulta de saldo atual da conta.")
    public record BalanceResponse(
            @Schema(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID accountId,
            @Schema(description = "Saldo atual disponível", example = "1250.00") BigDecimal balance,
            @Schema(description = "Moeda", example = "BRL") String currency) {
    }

    @Schema(description = "Resultado da auditoria de integridade da conta.")
    public record AuditResponse(
            @Schema(description = "UUID da conta auditada", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID accountId,
            @Schema(description = "Total de entradas registradas", example = "15") long entryCount,
            @Schema(description = "Saldo armazenado", example = "1250.00") BigDecimal storedBalance,
            @Schema(description = "Saldo recalculado por reprocessamento", example = "1250.00") BigDecimal replayedBalance,
            @Schema(description = "Versão atual da auditoria", example = "3") long version,
            @Schema(description = "Indica se o saldo está consistente", example = "true") boolean consistent,
            @Schema(description = "Lista de eventuais achados ou divergências") List<String> findings) {
    }

    // ---------------------------------------------------------------- accounts directory
    @Schema(description = "Item detalhado do diretório de contas do tenant.")
    public record AccountListItemResponse(
            @Schema(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") UUID accountId,
            @Schema(description = "Nome do cliente titular", example = "João da Silva") String customerName,
            @Schema(description = "Documento mascarado do titular", example = "***.686.290-**") String documentMasked,
            @Schema(description = "Conta formatada", example = "0001 / 1234567-5") String accountFormatted,
            @Schema(description = "Tipo da conta", example = "TRAN") String accountType,
            @Schema(description = "Status", example = "ACTIVE") String status,
            @Schema(description = "Saldo", example = "1250.00") BigDecimal balance,
            @Schema(description = "Moeda", example = "BRL") String currency,
            @Schema(description = "Data de criação", example = "2026-10-01T10:00:00Z") Instant createdAt) {

        static AccountListItemResponse from(ListAccountsUseCase.Item i) {
            return new AccountListItemResponse(i.accountId().value(), i.customerName(), i.documentMasked(),
                    i.accountFormatted(), i.accountType(), i.status(), i.balance().toDecimal(), i.currency(),
                    i.createdAt());
        }
    }

    @Schema(description = "Lista paginada de contas cadastradas no tenant.")
    public record AccountListResponse(
            @Schema(description = "Itens da página atual") List<AccountListItemResponse> items,
            @Schema(description = "Cursor para a próxima página", example = "d3ffdc88-5c0a-2ef6-bb6d-3bb9bd380f44") UUID nextCursor) {

        static AccountListResponse from(ListAccountsUseCase.Page page) {
            return new AccountListResponse(page.items().stream().map(AccountListItemResponse::from).toList(),
                    page.nextCursor() == null ? null : page.nextCursor().value());
        }
    }

    @Schema(description = "Dados para conferir se a conta existe, pode receber e pertence ao CPF/CNPJ informado (autorização de Pix).")
    public record HolderCheckRequest(
            @Schema(description = "Número da agência (4 dígitos)", example = "0001")
            @NotBlank @Pattern(regexp = "\\d{4}") String branch,

            @Schema(description = "Número da conta", example = "00100002")
            @NotBlank @Pattern(regexp = "\\d{1,20}") String number,

            @Schema(description = "Dígito verificador da conta", example = "9")
            @NotBlank @Pattern(regexp = "\\d") String checkDigit,

            @Schema(description = "CPF ou CNPJ do titular esperado (com ou sem máscara)", example = "529.982.247-25", maxLength = 32)
            @NotBlank @Size(max = 32) String taxId) {
    }

    @Schema(description = "Resultado da conferência de titularidade. Não devolve dados do titular.")
    public record HolderCheckResponse(
            @Schema(description = "VALID, ACCOUNT_NOT_FOUND, ACCOUNT_BLOCKED, ACCOUNT_CLOSED ou TAX_ID_MISMATCH", example = "VALID")
            String result,

            @Schema(description = "UUID da conta, presente apenas quando result = VALID", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
            UUID accountId) {

        static HolderCheckResponse from(QueryAccountUseCase.HolderCheck check) {
            return new HolderCheckResponse(check.result().name(),
                    check.accountId() == null ? null : check.accountId().value());
        }
    }
}
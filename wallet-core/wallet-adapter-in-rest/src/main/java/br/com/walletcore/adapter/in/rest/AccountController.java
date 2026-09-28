package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.AuditResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.BalanceResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.EntryResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.MoneyMovementRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.StatementResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts/{accountId}")
@Tag(name = "Accounts", description = "Endpoints de gerenciamento de contas, saldos, extratos, movimentações financeiras e auditoria.")
@SecurityRequirement(name = "bearerAuth") // Informa ao Swagger que os endpoints exigem autenticação JWT
class AccountController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final QueryAccountUseCase query;
    private final MoveMoneyUseCase moveMoney;
    private final AuditLedgerUseCase auditLedger;

    AccountController(QueryAccountUseCase query, MoveMoneyUseCase moveMoney, AuditLedgerUseCase auditLedger) {
        this.query = query;
        this.moveMoney = moveMoney;
        this.auditLedger = auditLedger;
    }

    @GetMapping
    @Operation(summary = "Obter detalhes da conta", description = "Retorna as informações cadastrais e metadados de uma conta específica pelo ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conta encontrada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada")
    })
    AccountResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId) {
        return AccountResponse.from(query.getAccount(CurrentTenant.from(jwt), new AccountId(accountId)));
    }

    @GetMapping("/balance")
    @Operation(summary = "Consultar saldo atual", description = "Retorna o saldo atual consolidado da conta informada.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Saldo obtido com sucesso"),
            @ApiResponse(responseCode = "401", description = "Não autorizado"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada")
    })
    BalanceResponse balance(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId) {
        Account account = query.getAccount(CurrentTenant.from(jwt), new AccountId(accountId));
        return new BalanceResponse(accountId, account.balance().toDecimal(), Money.CURRENCY);
    }

    @GetMapping("/statement")
    @Operation(summary = "Consultar extrato da conta", description = "Retorna a listagem de lançamentos e entradas da conta com suporte a paginação baseada em cursor (before).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Extrato retornado com sucesso"),
            @ApiResponse(responseCode = "401", description = "Não autorizado")
    })
    StatementResponse statement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @Parameter(description = "Cursor para paginação (identificador do último registro anterior)", required = false)
            @RequestParam(required = false) Long before,
            @Parameter(description = "Limite de registros retornados (padrão: 50)")
            @RequestParam(defaultValue = "50") int limit) {
        var statement = query.getStatement(CurrentTenant.from(jwt), new AccountId(accountId), before, limit);
        return new StatementResponse(statement.entries().stream().map(EntryResponse::from).toList(),
                statement.nextBefore());
    }

    @PostMapping("/deposits")
    @Operation(summary = "Realizar depósito", description = "Efetua uma operação de depósito na conta. Requer chave de idempotência no cabeçalho.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Depósito efetuado com sucesso (Novo recurso criado)"),
            @ApiResponse(responseCode = "200", description = "Requisição já processada anteriormente (Retorno de Idempotência / Replay)",
                    headers = @Header(name = REPLAYED_HEADER, description = "Indica se o evento foi recuperado por idempotência (true)", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "Dados da requisição inválidos ou chave de idempotência ausente")
    })
    ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência para evitar duplicidade de transações", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.deposit(new DepositCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Realizar saque", description = "Efetua uma operação de saque na conta, validando saldo disponível. Requer chave de idempotência.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Saque efetuado com sucesso"),
            @ApiResponse(responseCode = "200", description = "Requisição já processada anteriormente (Idempotent Replay)",
                    headers = @Header(name = REPLAYED_HEADER, description = "Indica se o evento foi recuperado por idempotência", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "422", description = "Saldo insuficiente ou conta inativa")
    })
    ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.withdraw(new WithdrawCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    /** Rebuilds the balance from the ledger events and compares it with the stored balance. */
    @GetMapping("/audit")
    @Operation(summary = "Auditar integridade do ledger", description = "Reconstrói o saldo a partir dos eventos do ledger e compara com o saldo armazenado, reportando eventuais inconsistências.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Auditoria executada com sucesso com o relatório de consistência"),
            @ApiResponse(responseCode = "401", description = "Não autorizado")
    })
    AuditResponse audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        var report = auditLedger.audit(CurrentTenant.from(jwt), new AccountId(accountId));
        return new AuditResponse(accountId, report.entryCount(), report.storedBalance().toDecimal(),
                report.replayedBalance().toDecimal(), report.storedVersion(), report.consistent(), report.findings());
    }

    static ResponseEntity<TransactionResponse> respond(TransactionResult r) {
        var body = new TransactionResponse(r.id().value(), r.type().name(), r.amount().toDecimal(), Money.CURRENCY,
                r.description(), r.occurredAt(), r.replayed());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (r.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
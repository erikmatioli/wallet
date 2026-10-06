package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountDetailResponse;
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
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
@Tag(name = "Account Operations", description = "Endpoints escopados a uma conta específica para consulta de dados, extratos, movimentações financeiras e auditoria.")
@SecurityRequirement(name = "bearerAuth")
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
    @Operation(
            summary = "Obter detalhes da conta",
            description = "Retorna os detalhes completos de uma conta específica, incluindo dados do saldo e informações mascaradas do titular."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Detalhes da conta retornados com sucesso"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    AccountDetailResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId) {
        return AccountDetailResponse.from(query.getAccountDetail(CurrentTenant.from(jwt), new AccountId(accountId)));
    }

    @GetMapping("/balance")
    @Operation(
            summary = "Consultar saldo atual",
            description = "Retorna o saldo atual disponível na conta informada."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Saldo consultado com sucesso"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    BalanceResponse balance(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId) {
        Account account = query.getAccount(CurrentTenant.from(jwt), new AccountId(accountId));
        return new BalanceResponse(accountId, account.balance().toDecimal(), Money.CURRENCY);
    }

    @GetMapping("/statement")
    @Operation(
            summary = "Consultar extrato da conta",
            description = "Retorna o extrato contábil (ledger) de lançamentos da conta de forma paginada baseada em cursor."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Extrato retornado com sucesso"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    StatementResponse statement(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Cursor numérico (before) para paginação de registros mais antigos", example = "15")
            @RequestParam(required = false) Long before,
            @Parameter(description = "Número máximo de registros por página", example = "50")
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Apenas estes tipos, separados por vírgula", example = "PIX_IN,PIX_OUT")
            @RequestParam(required = false) List<String> types,
            @Parameter(description = "Atalho para todos os tipos de um produto. Hoje só PIX (todos os PIX_*)", example = "PIX")
            @RequestParam(required = false) String product) {
        var statement = query.getStatement(CurrentTenant.from(jwt), new AccountId(accountId), before, limit,
                typeFilter(types, product));
        return new StatementResponse(statement.entries().stream().map(EntryResponse::from).toList(),
                statement.nextBefore());
    }

    /** Union of {@code types} and the types of {@code product}; null when neither was given (no filter). */
    private static Set<TransactionType> typeFilter(List<String> types, String product) {
        Set<TransactionType> filter = EnumSet.noneOf(TransactionType.class);
        if (product != null && !product.isBlank()) {
            if (!"PIX".equalsIgnoreCase(product.strip())) {
                throw new ValidationException("INVALID_PRODUCT", "product must be PIX");
            }
            Arrays.stream(TransactionType.values()).filter(TransactionType::isPix).forEach(filter::add);
        }
        if (types != null) {
            for (String t : types) {
                if (t.isBlank()) {
                    continue;
                }
                try {
                    filter.add(TransactionType.valueOf(t.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("INVALID_TRANSACTION_TYPE", "unknown transaction type: " + t.strip());
                }
            }
        }
        return filter.isEmpty() ? null : filter;
    }

    @PostMapping("/deposits")
    @Operation(
            summary = "Realizar depósito",
            description = "Efetua um depósito de valores na conta especificada. Requer cabeçalho obrigatório de chave de idempotência."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Depósito efetuado com sucesso (criado)"),
            @ApiResponse(responseCode = "200", description = "Depósito já processado anteriormente (retornado via idempotência)"),
            @ApiResponse(responseCode = "400", description = "Dados da requisição inválidos ou ausência da chave de idempotência"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência da requisição", example = "b82f099c-3a81-4e2b-912c-d9c49021b333", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.deposit(new DepositCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    @PostMapping("/withdrawals")
    @Operation(
            summary = "Realizar saque",
            description = "Efetua um saque de valores da conta especificada. Requer cabeçalho obrigatório de chave de idempotência e saldo suficiente."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Saque efetuado com sucesso (criado)"),
            @ApiResponse(responseCode = "200", description = "Saque já processado anteriormente (retornado via idempotência)"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos, saldo insuficiente ou ausência da chave de idempotência"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência da requisição", example = "c93g100d-4b92-5f3c-823d-e0d50132c444", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.withdraw(new WithdrawCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    /** Rebuilds the balance from the ledger events and compares it with the stored balance. */
    @GetMapping("/audit")
    @Operation(
            summary = "Auditar integridade da conta",
            description = "Reconstrói o saldo a partir dos eventos do ledger e o compara com o saldo armazenado, validando a consistência dos dados da conta."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Auditoria executada com sucesso"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    AuditResponse audit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", required = true)
            @PathVariable UUID accountId) {
        var report = auditLedger.audit(CurrentTenant.from(jwt), new AccountId(accountId));
        return new AuditResponse(accountId, report.entryCount(), report.storedBalance().toDecimal(),
                report.replayedBalance().toDecimal(), report.storedVersion(), report.consistent(), report.findings());
    }

    static ResponseEntity<TransactionResponse> respond(TransactionResult r) {
        var body = new TransactionResponse(r.id().value(), r.type().name(), r.amount().toDecimal(), Money.CURRENCY,
                r.description(), r.occurredAt(), r.replayed(), r.endToEndId());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (r.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}
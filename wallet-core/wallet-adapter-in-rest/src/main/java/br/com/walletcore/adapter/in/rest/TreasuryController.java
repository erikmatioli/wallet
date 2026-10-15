package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.adapter.in.rest.TreasuryModels.BackingAccountResponse;
import br.com.walletcore.adapter.in.rest.TreasuryModels.BackingStatementResponse;
import br.com.walletcore.adapter.in.rest.TreasuryModels.DailyPositionsResponse;
import br.com.walletcore.adapter.in.rest.TreasuryModels.PositionsResponse;
import br.com.walletcore.adapter.in.rest.TreasuryModels.RegisterBackingAccountRequest;
import br.com.walletcore.adapter.in.rest.TreasuryModels.TreasuryTransferRequest;
import br.com.walletcore.adapter.in.rest.TreasuryModels.UncoveredDailyResponse;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TreasuryTransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * The treasury (ADR-011): where the money that backs the customers should be, what a deposit created and nobody
 * covered yet, and the movements between backing accounts. Reads with {@code ledger:audit}; writes with
 * {@code treasury:write}, which a deposit's {@code ledger:write} does not give.
 */
@RestController
@RequestMapping("/v1/treasury")
@Tag(name = "Tesouraria", description = "Contas de lastro, dinheiro descoberto e movimentações de tesouraria")
@SecurityRequirement(name = "bearerAuth")
class TreasuryController {

    private final TreasuryUseCase treasury;
    private final MoveMoneyUseCase moveMoney;

    TreasuryController(TreasuryUseCase treasury, MoveMoneyUseCase moveMoney) {
        this.treasury = treasury;
        this.moveMoney = moveMoney;
    }

    /** The tenant's revenue account (ADR-014): what the fees took in, and its movements, newest first. */
    record RevenueResponse(BigDecimal balance, String currency, List<RevenueEntry> entries, Long next) {
    }

    record RevenueEntry(UUID transactionId, long sequence, String type, String direction, BigDecimal amount,
                        BigDecimal balanceAfter, String description, Instant occurredAt) {
    }

    @GetMapping("/revenue")
    @Operation(summary = "Conta de receitas do tenant",
            description = "O que as tarifas arrecadaram, já descontados os estornos, e os lançamentos, mais novos primeiro.")
    RevenueResponse revenue(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(required = false) Long before,
                            @RequestParam(defaultValue = "50") int limit) {
        var r = treasury.revenue(CurrentTenant.from(jwt), before, limit);
        return new RevenueResponse(r.balance().toDecimal(), Money.CURRENCY,
                r.entries().stream().map(e -> new RevenueEntry(e.transactionId().value(), e.sequence(), e.type().name(),
                        e.direction().name(), e.amount().toDecimal(), e.balanceAfter().toDecimal(), e.description(),
                        e.occurredAt())).toList(),
                r.next());
    }

    @GetMapping("/backing-accounts")
    @Operation(summary = "Contas de lastro e saldos esperados",
            description = "Cada conta de lastro com o saldo esperado agora e no fim de ontem. A soma é a posição do tenant.")
    PositionsResponse positions(@AuthenticationPrincipal Jwt jwt) {
        return PositionsResponse.from(treasury.positions(CurrentTenant.from(jwt)));
    }

    @PostMapping("/backing-accounts")
    @Operation(summary = "Cadastrar uma conta de lastro",
            description = "Reservas, CCME, uma conta de investimento ou a carteira de crédito. Conta PI, Caixa e "
                    + "A regularizar nascem com o tenant.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Cadastrada"),
            @ApiResponse(responseCode = "400", description = "Tipo ou nome inválido")
    })
    ResponseEntity<BackingAccountResponse> register(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody RegisterBackingAccountRequest request) {
        BackingType type;
        try {
            type = BackingType.valueOf(request.type());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_BACKING_TYPE", "unknown backing account type " + request.type());
        }
        var account = treasury.register(new TreasuryUseCase.RegisterCommand(CurrentTenant.from(jwt), type,
                request.name(), request.externalRef()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BackingAccountResponse.from(account));
    }

    @GetMapping("/backing-accounts/{id}/daily-balances")
    @Operation(summary = "Saldo esperado no fim de cada dia",
            description = "De from a to (inclusive), até 366 dias encerrados (ADR-012).")
    DailyPositionsResponse dailyBalances(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Parameter(description = "Primeiro dia (AAAA-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Último dia (AAAA-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return DailyPositionsResponse.from(id,
                treasury.dailyPositions(CurrentTenant.from(jwt), new BackingAccountId(id), from, to));
    }

    @GetMapping("/backing-accounts/{id}/entries")
    @Operation(summary = "Lançamentos de uma conta de lastro",
            description = "Do mais novo para o mais antigo, com o cliente de cada movimento e, nas movimentações de "
                    + "tesouraria, a outra conta e o comprovante. Na A regularizar: cada depósito e cada cobertura.")
    BackingStatementResponse entries(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Parameter(description = "Cursor devolvido em next pela página anterior") @RequestParam(required = false) String before,
            @Parameter(description = "Quantidade (1 a 200)") @RequestParam(defaultValue = "50") int limit) {
        return BackingStatementResponse.from(
                treasury.statement(CurrentTenant.from(jwt), new BackingAccountId(id), before, limit));
    }

    @GetMapping("/unassigned/daily")
    @Operation(summary = "Dinheiro descoberto dia a dia",
            description = "Por dia: quanto os depósitos criaram, quanto a tesouraria cobriu e o descoberto no fim do dia.")
    UncoveredDailyResponse uncoveredDaily(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return UncoveredDailyResponse.from(treasury.uncoveredDaily(CurrentTenant.from(jwt), from, to));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Movimentação de tesouraria",
            description = "Move valor entre duas contas de lastro. Saindo de A regularizar é uma cobertura (até o "
                    + "descoberto); entre as outras, uma recomposição. Nenhum cliente é tocado.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Registrada"),
            @ApiResponse(responseCode = "200", description = "Replay de idempotência"),
            @ApiResponse(responseCode = "404", description = "Conta de lastro não encontrada"),
            @ApiResponse(responseCode = "422", description = "Cobertura maior que o descoberto, destino A regularizar ou origem igual ao destino")
    })
    ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody TreasuryTransferRequest request) {
        TreasuryReference reference = request.reference() == null ? null
                : new TreasuryReference(request.reference().document(), request.reference().note());
        var result = moveMoney.treasuryTransfer(new TreasuryTransferCommand(CurrentTenant.from(jwt),
                new BackingAccountId(request.from()), new BackingAccountId(request.to()),
                Money.ofDecimal(request.amount()), request.description(), reference, idempotencyKey));
        return AccountController.respond(result);
    }
}

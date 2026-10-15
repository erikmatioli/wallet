package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeRefundCommand;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The fees of the fee basket (ADR-014): charged and given back by wallet-fees, with fees:write. */
@RestController
@Tag(name = "Fees", description = "Cobrança e estorno de tarifas pelo wallet-fees (ADR-014).")
@SecurityRequirement(name = "bearerAuth")
class FeeController {

    private final MoveMoneyUseCase moveMoney;

    FeeController(MoveMoneyUseCase moveMoney) {
        this.moveMoney = moveMoney;
    }

    @Schema(description = "Uma tarifa a cobrar da conta.")
    record FeeRequest(
            @Schema(description = "Valor", example = "1.50")
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Schema(description = "Operação da cesta de tarifas", example = "PIX_OUT")
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String operation,
            @Schema(description = "O que o cliente lê no extrato", example = "Tarifa: Pix enviado")
            @NotBlank @Size(max = 140) String description,
            @Schema(description = "A transação que gerou a tarifa; ausente na mensalidade") UUID sourceTransactionId,
            @Schema(description = "Referência livre, como o mês da mensalidade", example = "2026-10") @Size(max = 40) String reference) {
    }

    @Schema(description = "O estorno de uma tarifa.")
    record FeeRefundRequest(
            @Schema(description = "O que o cliente lê no extrato", example = "Estorno de tarifa: Pix enviado")
            @Size(max = 140) String description) {
    }

    @PostMapping("/v1/accounts/{accountId}/fees")
    @Operation(summary = "Cobrar uma tarifa",
            description = "Debita a conta e credita a conta de receitas do tenant. Só de conta sem bloqueio (ou com o "
                    + "bloqueio de tarifa) e só inteira. Exige Idempotency-Key, derivada da origem da tarifa.")
    ResponseEntity<TransactionResponse> fee(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
                                            @Valid @RequestBody FeeRequest request) {
        var result = moveMoney.fee(new FeeCommand(CurrentTenant.from(jwt), new AccountId(accountId),
                Money.ofDecimal(request.amount()), request.operation(), request.description(),
                request.sourceTransactionId() == null ? null : new TransactionId(request.sourceTransactionId()),
                request.reference(), idempotencyKey));
        return AccountController.respond(result);
    }

    @PostMapping("/v1/fees/{feeTransactionId}/refunds")
    @Operation(summary = "Estornar uma tarifa",
            description = "Devolve a tarifa inteira, uma vez: repetir devolve o estorno original. Entra mesmo com a "
                    + "conta bloqueada.")
    ResponseEntity<TransactionResponse> refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID feeTransactionId,
                                               @Valid @RequestBody(required = false) FeeRefundRequest request) {
        var result = moveMoney.feeRefund(new FeeRefundCommand(CurrentTenant.from(jwt),
                new TransactionId(feeTransactionId), request == null ? null : request.description()));
        return AccountController.respond(result);
    }
}

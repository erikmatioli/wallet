package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.ReversalCommand;
import br.com.walletcore.domain.shared.TransactionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reversal of a withdrawal. Generic on purpose - the core knows nothing about Pix: whoever owns a
 * multi-step payment (e.g. the Pix service, which debits before sending and must undo the debit
 * when the payee's side rejects) debits with a withdrawal and, if needed, reverses that exact
 * withdrawal here. No Idempotency-Key header: the withdrawal id itself makes it idempotent.
 */
@RestController
@RequestMapping("/v1/transactions/{transactionId}")
@Tag(name = "Transactions", description = "Operações sobre transações já lançadas.")
@SecurityRequirement(name = "bearerAuth")
class ReversalController {

    private final MoveMoneyUseCase moveMoney;

    ReversalController(MoveMoneyUseCase moveMoney) {
        this.moveMoney = moveMoney;
    }

    @Schema(description = "Dados opcionais do estorno.")
    record ReversalRequest(
            @Schema(description = "Descrição exibida no extrato", example = "Estorno de Pix não concluído", maxLength = 140)
            @Size(max = 140) String description) {
    }

    @PostMapping("/reversals")
    @Operation(
            summary = "Estornar um saque",
            description = "Credita de volta, integralmente, a conta debitada por um saque (WITHDRAWAL). Cada saque só pode ser estornado uma vez: repetir a chamada devolve o estorno original (200). Aceita os escopos ledger:write ou pix:send."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Estorno lançado"),
            @ApiResponse(responseCode = "200", description = "Esse saque já tinha sido estornado (retorno do estorno original)"),
            @ApiResponse(responseCode = "404", description = "Saque não encontrado para este tenant"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    ResponseEntity<TransactionResponse> reverse(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID do saque a estornar", required = true) @PathVariable UUID transactionId,
            @Valid @RequestBody(required = false) ReversalRequest request) {
        return AccountController.respond(moveMoney.reverseWithdrawal(new ReversalCommand(CurrentTenant.from(jwt),
                new TransactionId(transactionId), request == null ? null : request.description())));
    }
}

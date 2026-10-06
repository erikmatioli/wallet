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
import jakarta.validation.constraints.Pattern;
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
 * Reversal of a debit: a WITHDRAWAL (reversed with a DEPOSIT) or a PIX_OUT (reversed with a
 * PIX_REFUND that keeps the Pix detail, ADR-010). Whoever owns a multi-step payment (e.g. the Pix
 * service, which debits before sending and must undo the debit when the SPI rejects) reverses
 * that exact debit here. No Idempotency-Key header: the debit id itself makes it idempotent.
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
            @Size(max = 140) String description,
            @Schema(description = "Motivo do estorno no SPI (usado apenas no estorno de um PIX_OUT)", example = "AC03")
            @Pattern(regexp = "[A-Z0-9]{2,8}") String reasonCode) {
    }

    @PostMapping("/reversals")
    @Operation(
            summary = "Estornar um saque ou um Pix enviado",
            description = "Credita de volta, integralmente, a conta debitada por um saque (WITHDRAWAL, estorno como DEPOSIT) ou por um Pix enviado (PIX_OUT, estorno como PIX_REFUND com o detalhe do Pix; recusado com 422 se o Pix já teve devolução). Cada débito só pode ser estornado uma vez: repetir a chamada devolve o estorno original (200). Aceita os escopos ledger:write ou pix:send."
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
                new TransactionId(transactionId), request == null ? null : request.description(),
                request == null ? null : request.reasonCode())));
    }
}

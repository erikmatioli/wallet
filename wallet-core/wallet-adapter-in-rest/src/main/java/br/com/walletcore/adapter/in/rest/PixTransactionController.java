package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.PixCounterpartyRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.PixTransactionRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pix movements (ADR-010). Credits and debits are separate endpoints so each one can be granted
 * on its own scope: {@code pix:receive} can only credit an incoming Pix or a return,
 * {@code pix:send} can only debit a Pix or a return sent. The refund of a PIX_OUT is the
 * reversal of that debit ({@code POST /v1/transactions/{id}/reversals}).
 */
@RestController
@RequestMapping("/v1/accounts/{accountId}")
@Tag(name = "Pix", description = "Lançamentos Pix com tipo próprio e detalhe da contraparte (ADR-010).")
@SecurityRequirement(name = "bearerAuth")
class PixTransactionController {

    private static final Set<TransactionType> CREDITS = Set.of(TransactionType.PIX_IN, TransactionType.PIX_RETURN_IN);
    private static final Set<TransactionType> DEBITS = Set.of(TransactionType.PIX_OUT, TransactionType.PIX_RETURN_OUT);

    private final MoveMoneyUseCase moveMoney;

    PixTransactionController(MoveMoneyUseCase moveMoney) {
        this.moveMoney = moveMoney;
    }

    @PostMapping("/pix-credits")
    @Operation(
            summary = "Creditar um Pix",
            description = "Lança um Pix recebido (PIX_IN) ou uma devolução recebida (PIX_RETURN_IN) com a contraparte. Uma devolução precisa apontar para um PIX_OUT da mesma conta, não estornado, e as devoluções somadas não passam do valor original. Escopo pix:receive."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Pix lançado"),
            @ApiResponse(responseCode = "200", description = "Já lançado com esta Idempotency-Key (replay)"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos ou tipo não aceito neste endpoint"),
            @ApiResponse(responseCode = "404", description = "Conta ou Pix original não encontrado"),
            @ApiResponse(responseCode = "409", description = "Este Pix já foi lançado com outra Idempotency-Key"),
            @ApiResponse(responseCode = "422", description = "Regra do Pix violada (ex.: devolução maior que o original)")
    })
    ResponseEntity<TransactionResponse> credit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência", required = true)
            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody PixTransactionRequest request) {
        return post(jwt, accountId, idempotencyKey, request, CREDITS);
    }

    @PostMapping("/pix-debits")
    @Operation(
            summary = "Debitar um Pix",
            description = "Lança um Pix enviado (PIX_OUT) ou uma devolução enviada (PIX_RETURN_OUT) com a contraparte. Uma devolução precisa apontar para um PIX_IN da mesma conta, e as devoluções somadas não passam do valor original. Exige saldo. Escopo pix:send."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Pix lançado"),
            @ApiResponse(responseCode = "200", description = "Já lançado com esta Idempotency-Key (replay)"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos ou tipo não aceito neste endpoint"),
            @ApiResponse(responseCode = "404", description = "Conta ou Pix original não encontrado"),
            @ApiResponse(responseCode = "409", description = "Este Pix já foi lançado com outra Idempotency-Key"),
            @ApiResponse(responseCode = "422", description = "Saldo insuficiente, conta inativa ou regra do Pix violada")
    })
    ResponseEntity<TransactionResponse> debit(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId,
            @Parameter(description = "Chave única de idempotência", required = true)
            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody PixTransactionRequest request) {
        return post(jwt, accountId, idempotencyKey, request, DEBITS);
    }

    private ResponseEntity<TransactionResponse> post(Jwt jwt, UUID accountId, String idempotencyKey,
                                                     PixTransactionRequest r, Set<TransactionType> allowed) {
        if (!allowed.contains(r.type())) {
            throw new ValidationException("INVALID_PIX_TYPE", r.type() + " is not accepted by this endpoint");
        }
        PixCounterpartyRequest c = r.counterparty();
        PixDetail detail = new PixDetail(r.type(), r.endToEndId(), r.returnId(),
                r.relatedTransactionId() == null ? null : new TransactionId(r.relatedTransactionId()),
                PixCounterparty.of(c.name(), c.taxId(), c.ispb(), c.branch(), c.account(), c.accountType()),
                r.reasonCode(), r.remittanceInfo());
        return AccountController.respond(moveMoney.postPix(new PixCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(r.amount()), r.description(), idempotencyKey, detail)));
    }
}

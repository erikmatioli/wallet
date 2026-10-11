package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.AccountControlModels.JudicialTransitOutRequest;
import br.com.walletcore.adapter.in.rest.AccountControlModels.PendingTransitListResponse;
import br.com.walletcore.adapter.in.rest.AccountControlModels.PendingTransitResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransitOutCommand;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Depósitos judiciais a transferir" (ADR-013, item 3): the judicial transfers to a court deposit in another bank, and
 * the TED the treasury sends for each, recorded here because the core has no TED.
 */
@RestController
@RequestMapping("/v1/judicial-transit")
@Tag(name = "Judicial Transit", description = "Depósitos judiciais a transferir e o registro das TEDs (ADR-013).")
@SecurityRequirement(name = "bearerAuth")
class JudicialTransitController {

    private final AccountControlUseCase control;
    private final MoveMoneyUseCase moveMoney;

    JudicialTransitController(AccountControlUseCase control, MoveMoneyUseCase moveMoney) {
        this.control = control;
        this.moveMoney = moveMoney;
    }

    @GetMapping("/pending")
    @Operation(summary = "Transferências judiciais com TED a registrar", description = "As mais antigas primeiro.")
    PendingTransitListResponse pending(@AuthenticationPrincipal Jwt jwt) {
        return new PendingTransitListResponse(control.pendingJudicialTransit(CurrentTenant.from(jwt)).stream()
                .map(PendingTransitResponse::from).toList());
    }

    @PostMapping("/{transferId}/teds")
    @Operation(summary = "Registrar a TED de uma transferência judicial",
            description = "Tira o valor do trânsito contra a conta de lastro de onde a TED saiu. As TEDs de uma "
                    + "transferência não passam do valor dela. Exige Idempotency-Key.")
    ResponseEntity<TransactionResponse> ted(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID transferId,
                                            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
                                            @Valid @RequestBody JudicialTransitOutRequest request) {
        var result = moveMoney.judicialTransitOut(new JudicialTransitOutCommand(CurrentTenant.from(jwt),
                new TransactionId(transferId), new BackingAccountId(request.backingAccountId()),
                Money.ofDecimal(request.amount()), request.document(), request.note(),
                new Actor(request.operator(), CurrentTenant.clientId(jwt)), idempotencyKey));
        return AccountController.respond(result);
    }
}

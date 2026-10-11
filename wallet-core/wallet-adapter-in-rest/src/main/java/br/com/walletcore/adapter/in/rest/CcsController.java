package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.AccountControlModels.CcsEventResponse;
import br.com.walletcore.adapter.in.rest.AccountControlModels.CcsFeedResponse;
import br.com.walletcore.application.port.in.AccountControlUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The feed wallet-ccs reads (ADR-013, item 6): the openings and closings of the accounts, in order. */
@RestController
@RequestMapping("/v1/ccs")
@Tag(name = "CCS", description = "Feed de aberturas e encerramentos para o wallet-ccs.")
@SecurityRequirement(name = "bearerAuth")
class CcsController {

    private final AccountControlUseCase control;

    CcsController(AccountControlUseCase control) {
        this.control = control;
    }

    @GetMapping("/account-events")
    @Operation(summary = "Aberturas e encerramentos depois de after",
            description = "Em ordem de seq. Traz o documento completo e o nome do titular: só com o escopo ccs:read.")
    CcsFeedResponse events(@AuthenticationPrincipal Jwt jwt,
                           @Parameter(description = "O next da página anterior; 0 no começo", example = "0")
                           @RequestParam(defaultValue = "0") long after,
                           @RequestParam(defaultValue = "500") int limit) {
        var feed = control.ccsEvents(CurrentTenant.from(jwt), after, limit);
        return new CcsFeedResponse(feed.events().stream().map(CcsEventResponse::from).toList(), feed.next());
    }
}

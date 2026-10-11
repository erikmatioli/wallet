package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.ClosingBalanceResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.DailyBalancesResponse;
import br.com.walletcore.application.port.in.BalanceHistoryUseCase;
import br.com.walletcore.domain.shared.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The balance at the end of a day, read from the ledger when asked (ADR-012). {@code GET .../balance} without
 * {@code at} is still the current balance ({@link AccountController}); with {@code at}, it is that day's
 * closing balance.
 */
@RestController
@RequestMapping("/v1/accounts/{accountId}")
@Tag(name = "Contas", description = "Saldo, extrato e operações de uma conta")
@SecurityRequirement(name = "bearerAuth")
class BalanceHistoryController {

    private final BalanceHistoryUseCase history;

    BalanceHistoryController(BalanceHistoryUseCase history) {
        this.history = history;
    }

    @GetMapping(value = "/balance", params = "at")
    @Operation(
            summary = "Saldo de fechamento de um dia",
            description = "O saldo da conta no fim do dia informado (meia-noite de Brasília), calculado a partir do "
                    + "ledger. Só para dias encerrados: hoje e o futuro respondem 422 DAY_NOT_CLOSED."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Saldo de fechamento do dia"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "422", description = "O dia ainda não terminou (DAY_NOT_CLOSED)")
    })
    ClosingBalanceResponse closingBalance(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId,
            @Parameter(description = "Dia (AAAA-MM-DD)", example = "2026-12-31", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        return ClosingBalanceResponse.from(accountId,
                history.closingBalance(CurrentTenant.from(jwt), new AccountId(accountId), at));
    }

    @GetMapping("/daily-balances")
    @Operation(
            summary = "Saldo de fechamento dia a dia",
            description = "Um saldo de fechamento por dia, de from a to (inclusive), do mais antigo para o mais novo; "
                    + "dias sem movimento repetem o saldo anterior. Até 366 dias, todos encerrados."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Saldos do período"),
            @ApiResponse(responseCode = "400", description = "Período inválido ou maior que 366 dias (INVALID_PERIOD)"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada"),
            @ApiResponse(responseCode = "422", description = "O último dia ainda não terminou (DAY_NOT_CLOSED)")
    })
    DailyBalancesResponse dailyBalances(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da conta", required = true) @PathVariable UUID accountId,
            @Parameter(description = "Primeiro dia (AAAA-MM-DD)", example = "2026-10-01", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Último dia (AAAA-MM-DD)", example = "2026-10-31", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return DailyBalancesResponse.from(accountId,
                history.dailyBalances(CurrentTenant.from(jwt), new AccountId(accountId), from, to));
    }
}

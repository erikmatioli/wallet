package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountListResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.application.port.in.ListAccountsUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.domain.shared.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collection-level account endpoints (list, lookup-by-number) - separate from
 * {@link AccountController}, which is scoped to a single {@code {accountId}} path variable.
 */
@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts Directory", description = "Endpoints para listagem em lote de contas do tenant e busca por dados bancários.")
@SecurityRequirement(name = "bearerAuth")
class AccountDirectoryController {

    private final ListAccountsUseCase listAccounts;
    private final QueryAccountUseCase query;

    AccountDirectoryController(ListAccountsUseCase listAccounts, QueryAccountUseCase query) {
        this.listAccounts = listAccounts;
        this.query = query;
    }

    /** Every CUSTOMER account of the tenant, newest first. Cursor pagination (see ListAccountsUseCase). */
    @GetMapping
    @Operation(
            summary = "Listar contas do tenant",
            description = "Retorna uma lista paginada de todas as contas de clientes do tenant ordenadas da mais recente para a mais antiga, utilizando paginação por cursor."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lista de contas retornada com sucesso"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    AccountListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "UUID da última conta da página anterior utilizado como cursor", example = "d3ffdc88-5c0a-2ef6-bb6d-3bb9bd380f44")
            @RequestParam(required = false) UUID cursor,
            @Parameter(description = "Número máximo de registros a retornar na página", example = "20")
            @RequestParam(defaultValue = "20") int limit) {
        AccountId cursorId = cursor == null ? null : new AccountId(cursor);
        return AccountListResponse.from(listAccounts.list(CurrentTenant.from(jwt), cursorId, limit));
    }

    /** Finds one account by its bank-style number instead of its internal id. 404 if not found. */
    @GetMapping("/lookup")
    @Operation(
            summary = "Buscar conta por dados bancários",
            description = "Localiza e retorna os detalhes de uma conta utilizando a agência, o número da conta e o dígito verificador, em vez de seu ID interno."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conta encontrada com sucesso"),
            @ApiResponse(responseCode = "404", description = "Conta não encontrada para os dados bancários informados"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    AccountResponse lookup(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Número da agência bancária", example = "0001", required = true)
            @RequestParam String branch,
            @Parameter(description = "Número da conta", example = "1234567", required = true)
            @RequestParam String number,
            @Parameter(description = "Dígito verificador da conta", example = "5", required = true)
            @RequestParam String checkDigit) {
        return AccountResponse.from(query.getAccountByNumber(CurrentTenant.from(jwt), branch, number, checkDigit));
    }
}
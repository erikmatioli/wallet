package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.CustomerResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerResponse;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/customers")
@Tag(name = "Customers & Onboarding", description = "Endpoints para o cadastro de clientes e abertura de contas de pagamento.")
@SecurityRequirement(name = "bearerAuth")
class CustomerController {

    private final OnboardCustomerUseCase onboardCustomer;

    CustomerController(OnboardCustomerUseCase onboardCustomer) {
        this.onboardCustomer = onboardCustomer;
    }

    /** Onboards a customer and returns the newly opened payment account (conta de pagamento, TRAN). */
    @PostMapping
    @Operation(
            summary = "Realizar Onboard de Cliente e Abertura de Conta",
            description = "Cadastra um novo cliente no sistema e abre automaticamente uma conta de pagamento (TRAN) vinculada ao tenant."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Cliente cadastrado e conta aberta com sucesso",
                    headers = @Header(
                            name = "Location",
                            description = "URI para acessar os detalhes da conta recém-criada",
                            schema = @Schema(type = "string", example = "/v1/accounts/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Dados da requisição inválidos, faltando campos obrigatórios ou taxId duplicado/inválido"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido")
    })
    ResponseEntity<OnboardCustomerResponse> onboard(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OnboardCustomerRequest request) {
        Result result = onboardCustomer.onboard(new OnboardCustomerUseCase.Command(
                CurrentTenant.from(jwt), request.name(), request.taxId(), request.externalRef()));

        var customer = result.customer();
        var body = new OnboardCustomerResponse(
                new CustomerResponse(customer.id().value(), customer.name(), customer.taxId().type().name(),
                        customer.externalRef(), customer.status().name()),
                AccountResponse.from(result.account()));
        return ResponseEntity.created(URI.create("/v1/accounts/" + result.account().id().value())).body(body);
    }
}
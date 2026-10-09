package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.ChangeEmailRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.CustomerContactResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.CustomerResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerResponse;
import br.com.walletcore.application.port.in.CustomerContactUseCase;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase.Result;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.out.CustomerRepository.Contact;
import br.com.walletcore.domain.shared.CustomerId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/customers")
@Tag(name = "Customers & Onboarding", description = "Endpoints para o cadastro de clientes e abertura de contas de pagamento.")
@SecurityRequirement(name = "bearerAuth")
class CustomerController {

    private final OnboardCustomerUseCase onboardCustomer;
    private final CustomerContactUseCase contacts;

    CustomerController(OnboardCustomerUseCase onboardCustomer, CustomerContactUseCase contacts) {
        this.onboardCustomer = onboardCustomer;
        this.contacts = contacts;
    }

    /**
     * Who the customer with this CPF/CNPJ is and where codes may be sent to them (ADR-003 of wallet-app):
     * read by the app's backend before sending a login code. A GET with the document in the query, like
     * GET /v1/accounts/findByTaxId; only for the tenant's own backends.
     */
    @GetMapping("/contact")
    @Operation(summary = "Contato do cliente pelo CPF/CNPJ",
            description = "Nome, e-mail cadastrado pelo operador e conta de pagamento do cliente. 404 se o tenant não tem esse cliente.")
    CustomerContactResponse contact(@AuthenticationPrincipal Jwt jwt, @RequestParam String taxId) {
        Contact c = contacts.findByTaxId(CurrentTenant.from(jwt), taxId);
        return new CustomerContactResponse(c.customerId().value(), c.name(),
                c.email() == null ? null : c.email().value(), c.status().name(),
                c.accountId() == null ? null : c.accountId().value());
    }

    /** Sets, changes or removes (blank) the customer's email - the operator, from the console. */
    @PutMapping("/{customerId}/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Alterar o e-mail do cliente",
            description = "Define, troca ou (com e-mail vazio) remove o e-mail para onde os produtos do cliente mandam códigos.")
    void changeEmail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID customerId,
                     @Valid @RequestBody ChangeEmailRequest request) {
        contacts.changeEmail(CurrentTenant.from(jwt), new CustomerId(customerId), request.email());
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
                CurrentTenant.from(jwt), request.name(), request.taxId(), request.externalRef(), request.email()));

        var customer = result.customer();
        var body = new OnboardCustomerResponse(
                new CustomerResponse(customer.id().value(), customer.name(), customer.taxId().type().name(),
                        customer.externalRef(), customer.status().name(),
                        customer.email() == null ? null : customer.email().value()),
                AccountResponse.from(result.account()));
        return ResponseEntity.created(URI.create("/v1/accounts/" + result.account().id().value())).body(body);
    }
}
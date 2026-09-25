package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.CustomerResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.OnboardCustomerResponse;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase.Result;
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
class CustomerController {

    private final OnboardCustomerUseCase onboardCustomer;

    CustomerController(OnboardCustomerUseCase onboardCustomer) {
        this.onboardCustomer = onboardCustomer;
    }

    /** Onboards a customer and returns the newly opened payment account (conta de pagamento, TRAN). */
    @PostMapping
    ResponseEntity<OnboardCustomerResponse> onboard(@AuthenticationPrincipal Jwt jwt,
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

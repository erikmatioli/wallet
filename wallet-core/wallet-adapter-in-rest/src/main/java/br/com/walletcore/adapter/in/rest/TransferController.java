package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransferRequest;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransferCommand;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    private final MoveMoneyUseCase moveMoney;

    TransferController(MoveMoneyUseCase moveMoney) {
        this.moveMoney = moveMoney;
    }

    /** Book transfer between two accounts of the tenant; the destination is an id or an account number. */
    @PostMapping
    ResponseEntity<TransactionResponse> transfer(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
                                                 @Valid @RequestBody TransferRequest request) {
        boolean byId = request.destinationAccountId() != null;
        boolean byNumber = request.destination() != null;
        if (byId == byNumber) {
            throw new ValidationException("INVALID_DESTINATION",
                    "provide exactly one of destinationAccountId or destination");
        }
        Destination destination = byId
                ? new Destination.ById(new AccountId(request.destinationAccountId()))
                : new Destination.ByNumber(request.destination().branch(), request.destination().number(),
                        request.destination().checkDigit());

        var result = moveMoney.transfer(new TransferCommand(CurrentTenant.from(jwt),
                new AccountId(request.sourceAccountId()), destination, Money.ofDecimal(request.amount()),
                request.description(), idempotencyKey));
        return AccountController.respond(result);
    }
}

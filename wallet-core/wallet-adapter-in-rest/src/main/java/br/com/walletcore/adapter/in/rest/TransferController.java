package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransferRequest;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransferCommand;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Transfers", description = "Endpoints para transferências financeiras entre contas do sistema.")
@SecurityRequirement(name = "bearerAuth")
class TransferController {

    private final MoveMoneyUseCase moveMoney;

    TransferController(MoveMoneyUseCase moveMoney) {
        this.moveMoney = moveMoney;
    }

    /** Book transfer between two accounts of the tenant; the destination is an id or an account number. */
    @PostMapping
    @Operation(
            summary = "Realizar transferência entre contas",
            description = "Efetua uma transferência de fundos da conta de origem para uma conta de destino. O destino pode ser informado por ID interno (destinationAccountId) ou por dados bancários da agência/número (destination), sendo obrigatório informar exatamente um deles."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transferência efetuada com sucesso"),
            @ApiResponse(
                    responseCode = "200",
                    description = "Requisição já processada anteriormente (Retorno de Idempotência / Replay)",
                    headers = @Header(name = AccountController.REPLAYED_HEADER, description = "Indica se o evento foi recuperado por idempotência (true)", schema = @Schema(type = "string"))
            ),
            @ApiResponse(responseCode = "400", description = "Dados inválidos, chave de idempotência ausente ou fornecimento simultâneo/ausente de ambos os tipos de destino"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou inválido"),
            @ApiResponse(responseCode = "422", description = "Saldo insuficiente na origem ou conta de destino não encontrada")
    })
    ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Chave única de idempotência para evitar duplicidade na transferência", required = true)
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
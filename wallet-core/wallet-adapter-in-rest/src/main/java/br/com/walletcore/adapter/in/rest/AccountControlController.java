package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.AccountControlModels.BlockRequest;
import br.com.walletcore.adapter.in.rest.AccountControlModels.BlockResponse;
import br.com.walletcore.adapter.in.rest.AccountControlModels.BlocksResponse;
import br.com.walletcore.adapter.in.rest.AccountControlModels.CloseRequest;
import br.com.walletcore.adapter.in.rest.AccountControlModels.JudicialDestinationBody;
import br.com.walletcore.adapter.in.rest.AccountControlModels.JudicialTransferRequest;
import br.com.walletcore.adapter.in.rest.AccountControlModels.ReleaseRequest;
import br.com.walletcore.adapter.in.rest.AccountControlModels.TimelineItemResponse;
import br.com.walletcore.adapter.in.rest.AccountControlModels.TimelineResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.AccountDetailResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.in.AccountControlUseCase.BlockCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.CloseCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.ReleaseCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialDestination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransferCommand;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Blocks, judicial transfers and the closing of an account (ADR-013). */
@RestController
@RequestMapping("/v1/accounts/{accountId}")
@Tag(name = "Account Control", description = "Bloqueios, transferência judicial e encerramento da conta (ADR-013).")
@SecurityRequirement(name = "bearerAuth")
class AccountControlController {

    private static final Logger log = LoggerFactory.getLogger(AccountControlController.class);

    private final AccountControlUseCase control;
    private final MoveMoneyUseCase moveMoney;
    private final QueryAccountUseCase query;

    AccountControlController(AccountControlUseCase control, MoveMoneyUseCase moveMoney, QueryAccountUseCase query) {
        this.control = control;
        this.moveMoney = moveMoney;
        this.query = query;
    }

    @PostMapping("/blocks")
    @Operation(summary = "Bloquear a conta",
            description = "FRAUD e ADMINISTRATIVE exigem accounts:block; JUDICIAL exige accounts:judicial e o número do processo.")
    ResponseEntity<BlockResponse> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                        @Valid @RequestBody BlockRequest request) {
        var block = control.block(new BlockCommand(CurrentTenant.from(jwt), new AccountId(accountId), request.type(),
                request.reason(), request.note(), request.court() == null ? null : request.court().toDomain(),
                actor(jwt, request.operator()), CurrentTenant.scopes(jwt)));
        log.info("account blocked: account={} block={} type={}", accountId, block.id(), block.type());
        return ResponseEntity.status(HttpStatus.CREATED).body(BlockResponse.from(block));
    }

    @PostMapping("/blocks/{blockId}/release")
    @Operation(summary = "Liberar um bloqueio", description = "Com o mesmo escopo que cria um bloqueio do seu tipo.")
    BlockResponse release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId, @PathVariable UUID blockId,
                          @Valid @RequestBody ReleaseRequest request) {
        var block = control.release(new ReleaseCommand(CurrentTenant.from(jwt), new AccountId(accountId),
                new BlockId(blockId), request.note(), actor(jwt, request.operator()), CurrentTenant.scopes(jwt)));
        log.info("account block released: account={} block={} type={}", accountId, block.id(), block.type());
        return BlockResponse.from(block);
    }

    @GetMapping("/blocks")
    @Operation(summary = "Bloqueios da conta", description = "Mais novos primeiro; só os ativos com active=true.")
    BlocksResponse blocks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                          @RequestParam(defaultValue = "false") boolean active) {
        return new BlocksResponse(control.blocks(CurrentTenant.from(jwt), new AccountId(accountId), active).stream()
                .map(BlockResponse::from).toList());
    }

    @PostMapping("/judicial-transfers")
    @Operation(summary = "Transferência por ordem judicial",
            description = "Debita a conta com bloqueio judicial, acima dos bloqueios de fraude e administrativo, para outro "
                    + "cliente do tenant ou para os depósitos judiciais a transferir. Exige Idempotency-Key.")
    ResponseEntity<TransactionResponse> judicialTransfer(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
            @RequestHeader(AccountController.IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody JudicialTransferRequest request) {
        var result = moveMoney.judicialTransfer(new JudicialTransferCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), new BlockId(request.blockId()), Money.ofDecimal(request.amount()),
                destination(request.destination()), request.note(), actor(jwt, request.operator()), idempotencyKey));
        return AccountController.respond(result);
    }

    @PostMapping("/close")
    @Operation(summary = "Encerrar a conta",
            description = "Recusa com saldo (ACCOUNT_NOT_EMPTY) ou com bloqueio judicial ativo (JUDICIAL_BLOCK_ACTIVE).")
    AccountDetailResponse close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                @Valid @RequestBody CloseRequest request) {
        TenantId tenant = CurrentTenant.from(jwt);
        control.close(new CloseCommand(tenant, new AccountId(accountId), request.reason(), request.note(),
                actor(jwt, request.operator())));
        log.info("account closed: account={} reason={}", accountId, request.reason());
        return AccountDetailResponse.from(query.getAccountDetail(tenant, new AccountId(accountId)));
    }

    @GetMapping("/timeline")
    @Operation(summary = "Linha do tempo da conta", description = "Abertura, bloqueios, liberações e encerramento, mais novos primeiro.")
    TimelineResponse timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return new TimelineResponse(control.timeline(CurrentTenant.from(jwt), new AccountId(accountId)).stream()
                .map(TimelineItemResponse::from).toList());
    }

    private static Actor actor(Jwt jwt, String operator) {
        return new Actor(operator, CurrentTenant.clientId(jwt));
    }

    private static JudicialDestination destination(JudicialDestinationBody d) {
        return switch (d.kind()) {
            case JUDICIAL_TRANSIT -> new JudicialDestination.ToTransit();
            case ACCOUNT -> {
                if (d.accountId() != null) {
                    yield new JudicialDestination.ToAccount(new Destination.ById(new AccountId(d.accountId())));
                }
                if (d.branch() == null || d.number() == null || d.checkDigit() == null) {
                    throw new ValidationException("INVALID_DESTINATION",
                            "an ACCOUNT destination needs accountId, or branch, number and checkDigit");
                }
                yield new JudicialDestination.ToAccount(new Destination.ByNumber(d.branch(), d.number(), d.checkDigit()));
            }
        };
    }
}

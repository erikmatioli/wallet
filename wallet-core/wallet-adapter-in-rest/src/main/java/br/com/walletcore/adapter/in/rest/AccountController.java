package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.adapter.in.rest.ApiModels.AccountResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.AuditResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.BalanceResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.EntryResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.MoneyMovementRequest;
import br.com.walletcore.adapter.in.rest.ApiModels.StatementResponse;
import br.com.walletcore.adapter.in.rest.ApiModels.TransactionResponse;
import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import jakarta.validation.Valid;
import java.util.UUID;
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

@RestController
@RequestMapping("/v1/accounts/{accountId}")
class AccountController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final QueryAccountUseCase query;
    private final MoveMoneyUseCase moveMoney;
    private final AuditLedgerUseCase auditLedger;

    AccountController(QueryAccountUseCase query, MoveMoneyUseCase moveMoney, AuditLedgerUseCase auditLedger) {
        this.query = query;
        this.moveMoney = moveMoney;
        this.auditLedger = auditLedger;
    }

    @GetMapping
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        return AccountResponse.from(query.getAccount(CurrentTenant.from(jwt), new AccountId(accountId)));
    }

    @GetMapping("/balance")
    BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        Account account = query.getAccount(CurrentTenant.from(jwt), new AccountId(accountId));
        return new BalanceResponse(accountId, account.balance().toDecimal(), Money.CURRENCY);
    }

    @GetMapping("/statement")
    StatementResponse statement(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                @RequestParam(required = false) Long before,
                                @RequestParam(defaultValue = "50") int limit) {
        var statement = query.getStatement(CurrentTenant.from(jwt), new AccountId(accountId), before, limit);
        return new StatementResponse(statement.entries().stream().map(EntryResponse::from).toList(),
                statement.nextBefore());
    }

    @PostMapping("/deposits")
    ResponseEntity<TransactionResponse> deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                                @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                                @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.deposit(new DepositCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    @PostMapping("/withdrawals")
    ResponseEntity<TransactionResponse> withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
                                                 @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                                 @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResult result = moveMoney.withdraw(new WithdrawCommand(CurrentTenant.from(jwt),
                new AccountId(accountId), Money.ofDecimal(request.amount()), request.description(), idempotencyKey));
        return respond(result);
    }

    /** Rebuilds the balance from the ledger events and compares it with the stored balance. */
    @GetMapping("/audit")
    AuditResponse audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        var report = auditLedger.audit(CurrentTenant.from(jwt), new AccountId(accountId));
        return new AuditResponse(accountId, report.entryCount(), report.storedBalance().toDecimal(),
                report.replayedBalance().toDecimal(), report.storedVersion(), report.consistent(), report.findings());
    }

    static ResponseEntity<TransactionResponse> respond(TransactionResult r) {
        var body = new TransactionResponse(r.id().value(), r.type().name(), r.amount().toDecimal(), Money.CURRENCY,
                r.description(), r.occurredAt(), r.replayed());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (r.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}

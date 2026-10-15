package br.com.walletcore.domain.event;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** An account was closed, for good (ADR-013, published by ADR-014). */
public record AccountClosed(UUID eventId, UUID tenantId, UUID accountId, String reason, Instant occurredAt)
        implements DomainEvent {

    public static AccountClosed of(TenantId tenantId, AccountId accountId, String reason, Instant at) {
        return new AccountClosed(UuidV7.next(), tenantId.value(), accountId.value(), reason, at);
    }

    @Override
    public String aggregateType() {
        return "account";
    }

    @Override
    public String aggregateId() {
        return accountId.toString();
    }

    @Override
    public String eventType() {
        return "wallet.account.closed.v1";
    }
}

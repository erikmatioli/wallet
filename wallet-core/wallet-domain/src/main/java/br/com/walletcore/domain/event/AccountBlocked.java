package br.com.walletcore.domain.event;

import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.shared.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** A block was put on an account (ADR-013, published by ADR-014). No reason text nor court data: only what it is. */
public record AccountBlocked(UUID eventId, UUID tenantId, UUID accountId, UUID blockId, String blockType,
                             Instant occurredAt) implements DomainEvent {

    public static AccountBlocked of(AccountBlock block) {
        return new AccountBlocked(UuidV7.next(), block.tenantId().value(), block.accountId().value(),
                block.id().value(), block.type().name(), block.createdAt());
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
        return "wallet.account.blocked.v1";
    }
}

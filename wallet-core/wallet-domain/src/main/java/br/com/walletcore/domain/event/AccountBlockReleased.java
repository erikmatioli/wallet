package br.com.walletcore.domain.event;

import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.shared.UuidV7;
import java.time.Instant;
import java.util.UUID;

/**
 * A block was released (ADR-013, published by ADR-014). {@code stillBlocked} says whether other blocks remain, so a
 * consumer waiting for the account to be free (wallet-fees, to charge pending fees) knows without asking.
 */
public record AccountBlockReleased(UUID eventId, UUID tenantId, UUID accountId, UUID blockId, String blockType,
                                   boolean stillBlocked, Instant occurredAt) implements DomainEvent {

    public static AccountBlockReleased of(AccountBlock released, boolean stillBlocked) {
        return new AccountBlockReleased(UuidV7.next(), released.tenantId().value(), released.accountId().value(),
                released.id().value(), released.type().name(), stillBlocked, released.release().at());
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
        return "wallet.account.block-released.v1";
    }
}

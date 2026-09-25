package br.com.walletcore.domain.event;

import java.time.Instant;
import java.util.UUID;

public record CustomerOnboarded(
        UUID eventId,
        UUID tenantId,
        UUID customerId,
        UUID accountId,
        String documentType,
        String branch,
        String accountNumber,
        String checkDigit,
        String accountType,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "customer";
    }

    @Override
    public String aggregateId() {
        return customerId.toString();
    }

    @Override
    public String eventType() {
        return "wallet.customer.onboarded.v1";
    }
}

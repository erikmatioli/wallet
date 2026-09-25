package br.com.walletcore.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Integration-grade domain event, published through the transactional outbox. Payloads carry
 * only identifiers and non-sensitive attributes (no tax ids, no names).
 */
public sealed interface DomainEvent permits CustomerOnboarded, TransactionPosted {

    UUID eventId();

    UUID tenantId();

    String aggregateType();

    String aggregateId();

    String eventType();

    Instant occurredAt();
}

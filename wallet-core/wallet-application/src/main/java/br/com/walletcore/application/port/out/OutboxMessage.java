package br.com.walletcore.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                            String payload, Instant createdAt) {
}

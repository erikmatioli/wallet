package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.event.DomainEvent;
import java.util.List;

/** Stores events in the same database transaction as the state change (transactional outbox). */
public interface OutboxRepository {

    void enqueue(List<? extends DomainEvent> events);
}

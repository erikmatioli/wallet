package br.com.walletcore.application.port.out;

/**
 * Delivers outbox messages to a broker. Delivery is at-least-once: consumers must de-duplicate
 * on {@link OutboxMessage#id()}. Throw to signal failure; the message is retried.
 */
public interface EventPublisher {

    void publish(OutboxMessage message);
}

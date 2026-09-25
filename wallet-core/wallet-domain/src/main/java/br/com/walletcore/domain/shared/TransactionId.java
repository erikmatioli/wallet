package br.com.walletcore.domain.shared;

import java.util.Objects;
import java.util.UUID;

public record TransactionId(UUID value) {

    public TransactionId {
        Objects.requireNonNull(value, "transactionId");
    }

    public static TransactionId newId() {
        return new TransactionId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

package br.com.walletcore.domain.shared;

import java.util.Objects;
import java.util.UUID;

public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "customerId");
    }

    public static CustomerId newId() {
        return new CustomerId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

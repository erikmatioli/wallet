package br.com.walletcore.domain.shared;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "accountId");
    }

    public static AccountId newId() {
        return new AccountId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

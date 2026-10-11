package br.com.walletcore.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** A backing account (conta de lastro, ADR-011): the place, not one of its ledger shards. */
public record BackingAccountId(UUID value) {

    public BackingAccountId {
        Objects.requireNonNull(value, "backingAccountId");
    }

    public static BackingAccountId newId() {
        return new BackingAccountId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

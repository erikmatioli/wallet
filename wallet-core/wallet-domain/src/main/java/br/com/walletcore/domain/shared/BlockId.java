package br.com.walletcore.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** A block on an account (ADR-013). */
public record BlockId(UUID value) {

    public BlockId {
        Objects.requireNonNull(value, "blockId");
    }

    public static BlockId newId() {
        return new BlockId(UuidV7.next());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

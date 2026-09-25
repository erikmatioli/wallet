package br.com.walletcore.domain.shared;

import br.com.walletcore.domain.exception.ValidationException;
import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenantId");
    }

    public static TenantId newId() {
        return new TenantId(UuidV7.next());
    }

    public static TenantId of(String value) {
        try {
            return new TenantId(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("INVALID_TENANT_ID", "tenant id is not a valid UUID");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

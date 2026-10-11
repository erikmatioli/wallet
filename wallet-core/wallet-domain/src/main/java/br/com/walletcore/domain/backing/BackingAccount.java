package br.com.walletcore.domain.backing;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * A place where the tenant keeps money (ADR-011). Its balance lives in one or more internal ledger accounts
 * (shards, {@code Account.Kind.BACKING}), like the settlement did: summed, they are what the tenant should have
 * there. The sign follows the settlement's: negative is money held for the customers; the API shows it inverted,
 * as the "expected balance".
 *
 * @param externalRef where it is outside the ledger (institution, branch and account, or the central bank's
 *                    account id), for the reconciliation that comes later; may be null
 */
public record BackingAccount(
        BackingAccountId id,
        TenantId tenantId,
        BackingType type,
        String name,
        String externalRef,
        Instant createdAt) {

    private static final int MAX_NAME = 80;
    private static final int MAX_REF = 140;

    public BackingAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** One of the three every tenant is born with (Conta PI, Caixa, A regularizar). */
    public static BackingAccount openDefault(TenantId tenantId, BackingType type, Instant now) {
        if (!type.isDefault()) {
            throw new IllegalArgumentException(type + " is not created with the tenant");
        }
        return new BackingAccount(BackingAccountId.newId(), tenantId, type, type.defaultName(), null, now);
    }

    /** One the treasury registers: Reservas, CCME, an investment account, the credit portfolio. */
    public static BackingAccount register(TenantId tenantId, BackingType type, String name, String externalRef,
                                          Instant now) {
        if (type == null) {
            throw new ValidationException("INVALID_BACKING_TYPE", "type is required");
        }
        if (type.isDefault()) {
            throw new ValidationException("INVALID_BACKING_TYPE",
                    type + " is created with the tenant; there is exactly one");
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw new ValidationException("INVALID_NAME", "name must have between 1 and " + MAX_NAME + " characters");
        }
        String ref = externalRef == null || externalRef.isBlank() ? null : externalRef.strip();
        if (ref != null && ref.length() > MAX_REF) {
            throw new ValidationException("INVALID_EXTERNAL_REF",
                    "externalRef must have at most " + MAX_REF + " characters");
        }
        return new BackingAccount(BackingAccountId.newId(), tenantId, type, n, ref, now);
    }
}

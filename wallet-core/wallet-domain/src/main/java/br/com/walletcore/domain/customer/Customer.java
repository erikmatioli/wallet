package br.com.walletcore.domain.customer;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.TenantId;

import java.time.Instant;

/**
 * @param email where the customer's own products may send codes (ADR-003 of wallet-app); optional, set by
 *              the operator at onboarding or later, null when nobody registered one
 */
public record Customer(
        CustomerId id,
        TenantId tenantId,
        String name,
        TaxId taxId,
        String externalRef,
        Status status,
        Instant createdAt,
        Email email) {

    public enum Status { ACTIVE, BLOCKED }

    public static Customer onboard(TenantId tenantId, String name, TaxId taxId, String externalRef, Instant now) {
        return onboard(tenantId, name, taxId, externalRef, null, now);
    }

    public static Customer onboard(TenantId tenantId, String name, TaxId taxId, String externalRef, Email email,
                                   Instant now) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 140) {
            throw new ValidationException("INVALID_NAME", "name must have between 1 and 140 characters");
        }
        String ref = externalRef == null || externalRef.isBlank() ? null : externalRef.strip();
        if (ref != null && ref.length() > 64) {
            throw new ValidationException("INVALID_EXTERNAL_REF", "externalRef must have at most 64 characters");
        }
        return new Customer(CustomerId.newId(), tenantId, normalized, taxId, ref, Status.ACTIVE, now, email);
    }
}

package br.com.walletcore.domain.tenant;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A white-label client of the platform. Owns its customers, accounts and ledger, and
 * authenticates machine-to-machine with {@code clientId}/secret (HTTP Basic) to obtain a JWT.
 */
public record Tenant(
        TenantId id,
        String clientId,
        String secretHash,
        String name,
        String ispb,
        String branch,
        Set<String> scopes,
        Status status,
        Instant createdAt) {

    public enum Status { ACTIVE, SUSPENDED }

    /**
     * {@code pix:send} is a narrow scope for an outgoing-Pix context: debit (withdrawal) and
     * reversal of a debit only - no deposits, no transfers. Clients request it on its own
     * ({@code POST /v1/auth/token?scope=pix:send}) so that token cannot do anything else.
     *
     * <p>{@code pix:receive} is its incoming counterpart: credit an incoming Pix or a return
     * (PIX_IN, PIX_RETURN_IN), always with the Pix detail and the core's Pix rules - never a free
     * deposit, which stays {@code ledger:write} (ADR-010).
     *
     * <p>{@code schedules:read} and {@code schedules:write} are not checked by wallet-core itself:
     * they are for wallet-scheduler's API, which accepts wallet-core's tokens (ADR-001 of
     * wallet-scheduler) - wallet-core is the one identity provider of the platform.
     *
     * <p>{@code otp:use} is the same for wallet-otp's API: create and verify one-time codes
     * (ADR-001 of wallet-otp).
     */
    public static final Set<String> DEFAULT_SCOPES = Set.of("customers:write", "accounts:read", "ledger:write",
            "ledger:audit", "pix:send", "pix:receive", "schedules:read", "schedules:write", "otp:use");

    private static final Pattern CLIENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");
    private static final Pattern ISPB = Pattern.compile("[0-9]{8}");
    private static final Pattern BRANCH = Pattern.compile("[0-9]{4}");

    public Tenant {
        scopes = Set.copyOf(scopes);
    }

    public static Tenant create(TenantId id, String clientId, String secretHash, String name,
                                String ispb, String branch, Set<String> scopes, Instant now) {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
            throw new ValidationException("INVALID_CLIENT_ID",
                    "clientId must be 3-63 chars: lowercase letters, digits and hyphen");
        }
        if (name == null || name.isBlank() || name.length() > 140) {
            throw new ValidationException("INVALID_NAME", "name must have between 1 and 140 characters");
        }
        if (ispb == null || !ISPB.matcher(ispb).matches()) {
            throw new ValidationException("INVALID_ISPB", "ISPB must have 8 digits");
        }
        if (branch == null || !BRANCH.matcher(branch).matches()) {
            throw new ValidationException("INVALID_BRANCH", "branch (agencia) must have 4 digits");
        }
        return new Tenant(id, clientId, secretHash, name.strip(), ispb, branch,
                scopes == null || scopes.isEmpty() ? DEFAULT_SCOPES : scopes, Status.ACTIVE, now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}

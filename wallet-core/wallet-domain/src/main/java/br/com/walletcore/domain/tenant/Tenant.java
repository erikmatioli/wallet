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

    public static final Set<String> DEFAULT_SCOPES =
            Set.of("customers:write", "accounts:read", "ledger:write", "ledger:audit");

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

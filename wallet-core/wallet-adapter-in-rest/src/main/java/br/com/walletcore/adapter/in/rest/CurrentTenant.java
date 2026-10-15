package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.domain.shared.TenantId;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/** The tenant always comes from the signed token, never from request parameters. */
final class CurrentTenant {

    private CurrentTenant() {
    }

    static TenantId from(Jwt jwt) {
        return TenantId.of(jwt.getClaimAsString("tenant_id"));
    }

    /** The calling client: the token's subject. */
    static String clientId(Jwt jwt) {
        return jwt.getSubject();
    }

    /**
     * Whether the token may see the holder's full CPF/CNPJ: the operator's console, not the services that only move
     * money (they ask for a token without this scope).
     */
    static boolean seesDocument(Jwt jwt) {
        return scopes(jwt).contains("customers:document");
    }

    /** The scopes the token carries ("scope", space separated). */
    static Set<String> scopes(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        return scope == null || scope.isBlank() ? Set.of() : Set.of(scope.strip().split("\\s+"));
    }
}

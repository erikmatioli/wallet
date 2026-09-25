package br.com.walletcore.adapter.in.rest;

import br.com.walletcore.domain.shared.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;

/** The tenant always comes from the signed token, never from request parameters. */
final class CurrentTenant {

    private CurrentTenant() {
    }

    static TenantId from(Jwt jwt) {
        return TenantId.of(jwt.getClaimAsString("tenant_id"));
    }
}

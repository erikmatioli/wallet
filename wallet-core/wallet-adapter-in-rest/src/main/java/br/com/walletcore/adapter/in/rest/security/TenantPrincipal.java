package br.com.walletcore.adapter.in.rest.security;

import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.tenant.Tenant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The authenticated API client during the HTTP Basic step (client credentials). */
public final class TenantPrincipal implements UserDetails {

    private final TenantId tenantId;
    private final String clientId;
    private final String secretHash;
    private final Set<String> scopes;
    private final boolean enabled;

    TenantPrincipal(Tenant tenant) {
        this.tenantId = tenant.id();
        this.clientId = tenant.clientId();
        this.secretHash = tenant.secretHash();
        this.scopes = tenant.scopes();
        this.enabled = tenant.isActive();
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public Set<String> scopes() {
        return scopes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return secretHash;
    }

    @Override
    public String getUsername() {
        return clientId;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

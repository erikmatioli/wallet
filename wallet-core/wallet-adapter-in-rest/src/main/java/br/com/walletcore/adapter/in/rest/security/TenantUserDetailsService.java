package br.com.walletcore.adapter.in.rest.security;

import br.com.walletcore.application.port.out.TenantRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class TenantUserDetailsService implements UserDetailsService {

    private final TenantRepository tenants;

    TenantUserDetailsService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public UserDetails loadUserByUsername(String clientId) throws UsernameNotFoundException {
        return tenants.findByClientId(clientId)
                .map(TenantPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("unknown client"));
    }
}

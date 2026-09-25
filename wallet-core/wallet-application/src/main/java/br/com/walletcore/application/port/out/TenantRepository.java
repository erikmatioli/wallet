package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.tenant.Tenant;
import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    Optional<Tenant> findById(TenantId id);

    Optional<Tenant> findByClientId(String clientId);

    void insert(Tenant tenant);

    /** Ids of every ACTIVE tenant. Used by the periodic audit sweep to iterate tenants. */
    List<TenantId> findAllActiveIds();
}

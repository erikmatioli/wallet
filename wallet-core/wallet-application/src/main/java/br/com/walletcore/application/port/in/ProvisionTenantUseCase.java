package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.tenant.Tenant;
import java.util.Set;

/** Creates a white-label tenant together with its internal settlement accounts. */
public interface ProvisionTenantUseCase {

    Tenant provision(Command command);

    record Command(String clientId, String clientSecret, String name, String ispb, String branch,
                   int settlementShards, Set<String> scopes) {
    }
}

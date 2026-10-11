package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.tenant.Tenant;
import java.util.Set;

/**
 * Creates a white-label tenant together with its default backing accounts (ADR-011): Conta PI, Caixa and
 * A regularizar, each with {@code backingShards} internal ledger accounts.
 */
public interface ProvisionTenantUseCase {

    Tenant provision(Command command);

    record Command(String clientId, String clientSecret, String name, String ispb, String branch,
                   int backingShards, Set<String> scopes) {
    }
}

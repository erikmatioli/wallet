package br.com.walletcore.application.service;

import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spreads the counter-leg of deposits/withdrawals across the tenant's settlement shards so
 * that no single internal row serialises all traffic of a tenant.
 */
public final class SettlementRouter {

    private final AccountRepository accounts;
    private final ConcurrentMap<TenantId, List<AccountId>> cache = new ConcurrentHashMap<>();

    public SettlementRouter(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public AccountId pick(TenantId tenantId, TransactionId transactionId) {
        List<AccountId> shards = cache.computeIfAbsent(tenantId, t -> {
            List<AccountId> found = accounts.findSettlementAccountIds(t);
            if (found.isEmpty()) {
                throw new IllegalStateException("tenant " + t + " has no settlement accounts");
            }
            return found;
        });
        return shards.get(Math.floorMod(transactionId.value().hashCode(), shards.size()));
    }
}

package br.com.walletcore.application.service;

import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Picks the internal leg of a movement (ADR-011): the backing account of its rail (the Conta PI for a Pix, the cash
 * for a withdrawal, "a regularizar" for a deposit) and, inside it, one shard by the transaction id, so that no single
 * internal row serialises all traffic of a tenant. Backing accounts and their shards never change once created, so
 * both lookups are cached.
 */
public final class BackingRouter {

    private final BackingAccountRepository backing;
    private final ConcurrentMap<TenantId, ConcurrentMap<BackingType, BackingAccountId>> defaults = new ConcurrentHashMap<>();
    private final ConcurrentMap<BackingAccountId, List<AccountId>> shards = new ConcurrentHashMap<>();

    public BackingRouter(BackingAccountRepository backing) {
        this.backing = backing;
    }

    /** A shard of the tenant's default backing account of {@code type} (Conta PI, Caixa or A regularizar). */
    public AccountId pick(TenantId tenantId, BackingType type, TransactionId transactionId) {
        return pick(tenantId, defaultAccount(tenantId, type), transactionId);
    }

    /** A shard of the given backing account. */
    public AccountId pick(TenantId tenantId, BackingAccountId backingAccount, TransactionId transactionId) {
        List<AccountId> found = shards.computeIfAbsent(backingAccount, id -> {
            List<AccountId> ids = backing.findShardIds(tenantId, id);
            if (ids.isEmpty()) {
                throw new IllegalStateException("backing account " + id + " has no shards");
            }
            return ids;
        });
        return found.get(Math.floorMod(transactionId.value().hashCode(), found.size()));
    }

    public BackingAccountId defaultAccount(TenantId tenantId, BackingType type) {
        if (!type.isDefault()) {
            throw new IllegalArgumentException(type + " is not a default backing account");
        }
        return defaults.computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>()).computeIfAbsent(type, ty ->
                backing.findAll(tenantId).stream()
                        .filter(b -> b.type() == ty)
                        .map(BackingAccount::id)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("tenant " + tenantId + " has no " + ty
                                + " backing account")));
    }
}

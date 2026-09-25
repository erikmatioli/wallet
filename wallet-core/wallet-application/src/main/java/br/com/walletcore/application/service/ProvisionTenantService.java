package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.SecretHasher;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.tenant.Tenant;
import java.time.Clock;
import java.time.Instant;

public final class ProvisionTenantService implements ProvisionTenantUseCase {

    private static final int MIN_SECRET_LENGTH = 16;
    private static final int MAX_SHARDS = 64;

    private final TransactionRunner tx;
    private final TenantRepository tenants;
    private final AccountRepository accounts;
    private final SecretHasher hasher;
    private final Clock clock;

    public ProvisionTenantService(TransactionRunner tx, TenantRepository tenants, AccountRepository accounts,
                                  SecretHasher hasher, Clock clock) {
        this.tx = tx;
        this.tenants = tenants;
        this.accounts = accounts;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Override
    public Tenant provision(Command c) {
        if (c.clientSecret() == null || c.clientSecret().length() < MIN_SECRET_LENGTH) {
            throw new ValidationException("WEAK_CLIENT_SECRET",
                    "clientSecret must have at least " + MIN_SECRET_LENGTH + " characters");
        }
        if (c.settlementShards() < 1 || c.settlementShards() > MAX_SHARDS) {
            throw new ValidationException("INVALID_SHARDS", "settlementShards must be between 1 and " + MAX_SHARDS);
        }
        Instant now = clock.instant();
        Tenant tenant = Tenant.create(TenantId.newId(), c.clientId(), hasher.hash(c.clientSecret()), c.name(),
                c.ispb(), c.branch(), c.scopes(), now);

        return tx.inTransaction(tenant.id(), () -> {
            if (tenants.findByClientId(tenant.clientId()).isPresent()) {
                throw new ConflictException("CLIENT_ID_ALREADY_EXISTS", "a tenant with this clientId already exists");
            }
            tenants.insert(tenant);
            for (int i = 0; i < c.settlementShards(); i++) {
                accounts.insert(Account.openSettlement(tenant.id(), now));
            }
            return tenant;
        });
    }
}

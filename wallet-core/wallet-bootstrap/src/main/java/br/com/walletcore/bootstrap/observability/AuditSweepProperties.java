package br.com.walletcore.bootstrap.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled     turn the sweep on/off without touching code (e.g. disable in an environment
 *                    that already runs its own reconciliation)
 * @param accountsPerTenant how many recently-active accounts to re-audit per tenant, per run.
 *                    Bounded on purpose: as the ledger grows, this stays a fixed-cost sample of
 *                    the accounts most likely to have just been written to, instead of a full
 *                    table scan that would grow forever with the business
 */
@ConfigurationProperties(prefix = "wallet.observability.audit-sweep")
public record AuditSweepProperties(boolean enabled, int accountsPerTenant) {

    public AuditSweepProperties {
        if (accountsPerTenant <= 0) {
            accountsPerTenant = 200;
        }
    }
}

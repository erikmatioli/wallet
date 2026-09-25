package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.shared.TenantId;
import java.util.function.Supplier;

/**
 * Runs work inside a database transaction bound to a tenant (row-level security scope).
 * Implementations retry transient failures (deadlock, lock timeout) by re-running the whole
 * supplier in a fresh transaction, so the work must be idempotent - which every money movement is.
 */
public interface TransactionRunner {

    <T> T inTransaction(TenantId tenantId, Supplier<T> work);

    /** Consistent read-only snapshot. */
    <T> T readOnly(TenantId tenantId, Supplier<T> work);
}

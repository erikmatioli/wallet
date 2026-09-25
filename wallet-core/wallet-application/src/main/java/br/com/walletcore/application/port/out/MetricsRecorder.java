package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.TenantId;

/**
 * Business-level observability port. The application layer reports *what happened* in domain
 * terms; the adapter decides how that becomes metrics, traces or logs (Micrometer in
 * {@code wallet-bootstrap}). Kept separate from {@link TransactionRunner} (which only cares
 * about infrastructure spans) so that use cases can emit business facts without knowing
 * Micrometer exists.
 */
public interface MetricsRecorder {

    /** A transaction was posted for the first time, or replayed via idempotency key. */
    void transactionPosted(TenantId tenantId, TransactionType type, boolean replayed);

    /** A money movement was rejected before posting. {@code reasonCode} matches the domain error code. */
    void transactionRejected(TenantId tenantId, TransactionType type, String reasonCode);

    /** A customer finished onboarding and a payment account was opened. */
    void customerOnboarded(TenantId tenantId);

    /** Result of a ledger audit (replay), whether triggered by the API or by the periodic sweep. */
    void auditCompleted(TenantId tenantId, boolean consistent, long findingsCount);
}

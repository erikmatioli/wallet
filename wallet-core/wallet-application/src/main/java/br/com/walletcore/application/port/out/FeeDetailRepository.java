package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.shared.TransactionId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.Optional;

/** What a fee and a fee refund carry besides the ledger (ADR-014). Append-only. */
public interface FeeDetailRepository {

    void insert(TenantId tenantId, TransactionId transactionId, Detail detail);

    Optional<Detail> findByTransaction(TenantId tenantId, TransactionId transactionId);

    /**
     * @param operation the fee basket's operation (PIX_OUT, WITHDRAWAL, MONTHLY...), as wallet-fees names it
     * @param source    for a fee, the transaction that caused it (null for a monthly fee); for a refund, the fee
     * @param reference free reference of the caller, such as the month of a monthly fee ("2026-10")
     */
    record Detail(String operation, TransactionId source, String reference) {
    }
}

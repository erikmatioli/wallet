package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** The Pix detail of each PIX_* transaction (ADR-010). Append-only, like the ledger. */
public interface PixDetailRepository {

    /**
     * Written in the same database transaction as the ledger entries.
     *
     * @throws br.com.walletcore.domain.exception.ConflictException when this Pix (type +
     *         EndToEndId + return id) was already posted for the tenant
     */
    void insert(TenantId tenantId, TransactionId transactionId, PixDetail detail);

    Optional<PixDetail> findByTransaction(TenantId tenantId, TransactionId transactionId);

    /** One batched lookup for a statement page. Transactions without detail are absent from the map. */
    Map<TransactionId, PixDetail> findByTransactions(TenantId tenantId, Collection<TransactionId> transactionIds);

    /** Whether this Pix (type + EndToEndId + return id) was already posted for the tenant. */
    boolean exists(TenantId tenantId, TransactionType type, String endToEndId, String returnId);

    /** Total, in cents, of the transactions of {@code type} that refer to {@code related}. */
    long sumRelated(TenantId tenantId, TransactionId related, TransactionType type);

    /**
     * Serializes, until the end of the current database transaction, everything that refers to
     * {@code original} - so two concurrent returns cannot both pass the "not more than the
     * original" check, and a refund cannot race a return.
     */
    void lockOriginal(TenantId tenantId, TransactionId original);
}

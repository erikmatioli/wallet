package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** What a judicial transfer and its TED carry besides the ledger (ADR-013, item 3). Append-only. */
public interface JudicialTransferRepository {

    void insert(TenantId tenantId, Detail detail);

    Optional<Detail> findByTransaction(TenantId tenantId, TransactionId transactionId);

    /** Serializes the TEDs of one judicial transfer, so two of them cannot both fit in what is left. */
    void lock(TenantId tenantId, TransactionId transferId);

    /** How much of a judicial transfer to the transit the TEDs already took out, in cents. */
    long sumSent(TenantId tenantId, TransactionId transferId);

    /** The judicial transfers to the transit whose TED was not (fully) sent yet, oldest first. */
    List<PendingTransit> findPendingTransit(TenantId tenantId);

    enum Kind { TRANSFER, TRANSIT_OUT }

    /**
     * @param accountId the blocked account (TRANSFER), null for a TRANSIT_OUT
     * @param toTransit whether a TRANSFER went to the judicial transit (a court deposit in another bank)
     * @param related   for a TRANSIT_OUT, the judicial transfer whose TED it records
     * @param document  for a TRANSIT_OUT, the TED's receipt
     */
    record Detail(TransactionId transactionId, Kind kind, AccountId accountId, BlockId blockId, String process,
                  String protocol, boolean toTransit, TransactionId related, Actor actor, String note,
                  String document) {
    }

    record PendingTransit(TransactionId transferId, AccountId accountId, String process, String protocol,
                          Money amount, Money sent, Instant occurredAt) {

        public Money remaining() {
            return Money.ofCents(amount.cents() - sent.cents());
        }
    }
}

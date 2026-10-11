package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.List;

/** The openings and closings of the accounts (ADR-013, item 4), append-only. */
public interface AccountStatusEventRepository {

    /**
     * Appends the event; its {@code seq} is ignored and assigned here. Two events of one tenant are given their
     * {@code seq} in the order they commit, so a reader that saw {@code seq} n never finds a smaller one later.
     */
    void append(TenantId tenantId, AccountStatusEvent event);

    /** The account's events, oldest first. */
    List<AccountStatusEvent> findByAccount(TenantId tenantId, AccountId accountId);

    /** The tenant's events after {@code afterSeq}, in {@code seq} order: the feed of wallet-ccs. */
    List<CcsAccountEvent> findAfter(TenantId tenantId, long afterSeq, int limit);

    /**
     * What the CCS needs about an opening or a closing (ADR-013, item 6), which the DomainEvent does not carry: the
     * holder's full document and name, and the account's number.
     */
    record CcsAccountEvent(long seq, AccountId accountId, Kind kind, Instant occurredAt, String holderName,
                           String taxIdType, String taxId, String ispb, String branch, String number,
                           String checkDigit, String accountType) {

        public enum Kind { OPENED, CLOSED }
    }
}

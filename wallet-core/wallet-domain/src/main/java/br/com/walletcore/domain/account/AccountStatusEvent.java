package br.com.walletcore.domain.account;

import br.com.walletcore.domain.shared.AccountId;
import java.time.Instant;

/**
 * A change of the account's status (ADR-013, item 4): its opening ({@code from} null) and its closing. Append-only;
 * {@code seq} is the order the CCS feed is read in.
 */
public record AccountStatusEvent(
        long seq,
        AccountId accountId,
        Account.Status from,
        Account.Status to,
        Instant occurredAt,
        Actor actor,
        CloseReason reason,
        String note) {

    public enum CloseReason { CUSTOMER_REQUEST, INSTITUTION_DECISION, DEATH, OTHER }

    public boolean isOpening() {
        return from == null;
    }
}

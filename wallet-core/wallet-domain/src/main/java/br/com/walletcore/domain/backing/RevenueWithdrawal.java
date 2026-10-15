package br.com.walletcore.domain.backing;

import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Objects;

/**
 * What the treasury did outside the ledger when it took the tenant's revenue out of the platform (ADR-015): the
 * backing account the money left from, the tenant's account it went to, the receipt and who did it.
 *
 * @param document the receipt (a TED number), required
 */
public record RevenueWithdrawal(TransactionId transactionId, BackingAccountId from, Destination destination,
                                String document, String note, Actor actor) {

    private static final int MAX_DOCUMENT = 140;
    private static final int MAX_NOTE = 280;

    public RevenueWithdrawal {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(actor, "actor");
        if (from == null) {
            throw new ValidationException("INVALID_BACKING_ACCOUNT", "the backing account the money left from is required");
        }
        if (destination == null) {
            throw new ValidationException("INVALID_DESTINATION", "the destination (bank, branch, account) is required");
        }
        document = strip(document);
        if (document == null || document.length() > MAX_DOCUMENT) {
            throw new ValidationException("DOCUMENT_REQUIRED", "the receipt is required, at most 140 characters");
        }
        note = strip(note);
        if (note != null && note.length() > MAX_NOTE) {
            throw new ValidationException("INVALID_NOTE", "note must have at most 280 characters");
        }
    }

    /** The tenant's account outside the platform, as the treasury wrote it. */
    public record Destination(String bank, String branch, String account) {

        public Destination {
            bank = required(bank, 80, "bank");
            branch = required(branch, 10, "branch");
            account = required(account, 30, "account");
        }

        /** "Itaú (341), ag 0001, conta 12345-6". */
        public String describe() {
            return bank + ", ag " + branch + ", conta " + account;
        }

        private static String required(String value, int max, String field) {
            String v = strip(value);
            if (v == null || v.length() > max) {
                throw new ValidationException("INVALID_DESTINATION",
                        "destination " + field + " is required, at most " + max + " characters");
            }
            return v;
        }
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

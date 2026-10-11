package br.com.walletcore.domain.backing;

import br.com.walletcore.domain.exception.ValidationException;

/**
 * What the treasury did outside the ledger to back a treasury transfer (ADR-011): the receipt (a TED number, a
 * redemption note) and a free note. The tenant's own money is not a customer balance and is not in the ledger, so
 * this is where its origin is written down.
 */
public record TreasuryReference(String document, String note) {

    private static final int MAX_DOCUMENT = 140;
    private static final int MAX_NOTE = 280;

    public TreasuryReference {
        document = blankToNull(document);
        note = blankToNull(note);
        if (document != null && document.length() > MAX_DOCUMENT) {
            throw new ValidationException("INVALID_REFERENCE", "document must have at most " + MAX_DOCUMENT + " characters");
        }
        if (note != null && note.length() > MAX_NOTE) {
            throw new ValidationException("INVALID_REFERENCE", "note must have at most " + MAX_NOTE + " characters");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

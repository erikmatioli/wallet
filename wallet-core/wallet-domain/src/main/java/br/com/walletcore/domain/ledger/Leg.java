package br.com.walletcore.domain.ledger;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import java.util.Objects;

/** One side of a double-entry posting. */
public record Leg(AccountId accountId, EntryDirection direction, Money amount) {

    public Leg {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new ValidationException("INVALID_AMOUNT", "amount must be greater than zero");
        }
    }

    /** Effect on the account balance, in cents. */
    public long signedCents() {
        return direction == EntryDirection.CREDIT ? amount.cents() : -amount.cents();
    }
}

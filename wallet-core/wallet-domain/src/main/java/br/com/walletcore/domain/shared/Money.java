package br.com.walletcore.domain.shared;

import br.com.walletcore.domain.exception.ValidationException;
import java.math.BigDecimal;

/**
 * Monetary amount in BRL stored as integer cents. Never uses floating point.
 * Balances of internal accounts may be negative; user-supplied amounts must be positive
 * (enforced by {@code Leg}).
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);
    public static final String CURRENCY = "BRL";

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public static Money ofDecimal(BigDecimal value) {
        if (value == null) {
            throw new ValidationException("INVALID_AMOUNT", "amount is required");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("INVALID_AMOUNT", "amount must have at most 2 decimal places");
        }
        try {
            return new Money(value.movePointRight(2).longValueExact());
        } catch (ArithmeticException e) {
            throw new ValidationException("INVALID_AMOUNT", "amount is out of range");
        }
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return new Money(Math.negateExact(cents));
    }

    public boolean isPositive() {
        return cents > 0;
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(cents, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return CURRENCY + " " + toDecimal().toPlainString();
    }
}

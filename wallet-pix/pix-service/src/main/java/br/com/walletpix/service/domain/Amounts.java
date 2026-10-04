package br.com.walletpix.service.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** BRL amounts travel as decimals on the bus and in wallet-core's API; this service stores cents. */
public final class Amounts {

    private Amounts() {
    }

    /** @throws IllegalArgumentException for more than 2 decimal places or a non-positive amount */
    public static long toCents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places: " + amount);
        }
        return amount.movePointRight(2).longValueExact();
    }

    public static BigDecimal toDecimal(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }
}

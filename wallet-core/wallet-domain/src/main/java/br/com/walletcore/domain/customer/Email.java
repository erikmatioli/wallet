package br.com.walletcore.domain.customer;

import br.com.walletcore.domain.exception.ValidationException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The customer's email, as the operator registered it (ADR-003 of wallet-app): the address a product may
 * send codes to, because it came from someone who knows the customer - not from whoever is asking.
 * Stored lower-case; {@link #toString()} masks it, so it never lands in full in a log line.
 */
public record Email(String value) {

    private static final Pattern FORMAT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int MAX_LENGTH = 254;

    public Email {
        if (value == null || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new ValidationException("INVALID_EMAIL", "email must be a valid address");
        }
    }

    /** Trims and lower-cases; null or blank is "no email". */
    public static Email parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new Email(raw.strip().toLowerCase(Locale.ROOT));
    }

    /** "m***@example.com". */
    public String masked() {
        return value.charAt(0) + "***" + value.substring(value.indexOf('@'));
    }

    @Override
    public String toString() {
        return masked();
    }
}

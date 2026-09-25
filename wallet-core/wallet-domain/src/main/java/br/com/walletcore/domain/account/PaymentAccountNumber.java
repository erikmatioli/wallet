package br.com.walletcore.domain.account;

import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ValidationException;
import java.util.regex.Pattern;

/**
 * Identifies a payment account the way PIX/SPI expects it: participant ISPB (8 digits), branch
 * (4 digits), account number, check digit and account type (TRAN).
 *
 * <p>Brazil does not mandate a single check-digit algorithm; each institution defines its own.
 * This implementation uses modulo 11 over branch + number (weights 2..9 from the right).
 * Replace {@link #computeCheckDigit(String)} if your institution's rule differs.
 */
public record PaymentAccountNumber(String ispb, String branch, String number, String checkDigit, AccountType type) {

    private static final Pattern ISPB = Pattern.compile("[0-9]{8}");
    private static final Pattern BRANCH = Pattern.compile("[0-9]{4}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,20}");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final long MAX_SEQUENCE = 99_999_999L;

    public PaymentAccountNumber {
        if (ispb == null || !ISPB.matcher(ispb).matches()) {
            throw new ValidationException("INVALID_ISPB", "ISPB must have 8 digits");
        }
        if (branch == null || !BRANCH.matcher(branch).matches()) {
            throw new ValidationException("INVALID_BRANCH", "branch must have 4 digits");
        }
        if (number == null || !NUMBER.matcher(number).matches()) {
            throw new ValidationException("INVALID_ACCOUNT_NUMBER", "account number must be numeric (max 20 digits)");
        }
        if (checkDigit == null || !DIGIT.matcher(checkDigit).matches()) {
            throw new ValidationException("INVALID_CHECK_DIGIT", "check digit must be a single digit");
        }
        if (type == null) {
            throw new ValidationException("INVALID_ACCOUNT_TYPE", "account type is required");
        }
    }

    public static PaymentAccountNumber generate(String ispb, String branch, long sequence, AccountType type) {
        if (sequence <= 0 || sequence > MAX_SEQUENCE) {
            throw new BusinessRuleException("ACCOUNT_NUMBER_EXHAUSTED", "account number sequence is exhausted");
        }
        String number = String.format("%08d", sequence);
        return new PaymentAccountNumber(ispb, branch, number, computeCheckDigit(branch + number), type);
    }

    public static String computeCheckDigit(String base) {
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int digit = 11 - (sum % 11);
        return String.valueOf(digit >= 10 ? 0 : digit);
    }

    /** Human readable form: branch-number-digit. */
    public String formatted() {
        return branch + "-" + number + "-" + checkDigit;
    }
}

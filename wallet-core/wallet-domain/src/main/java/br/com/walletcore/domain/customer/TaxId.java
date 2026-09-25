package br.com.walletcore.domain.customer;

import br.com.walletcore.domain.exception.ValidationException;
import java.util.Locale;

/**
 * CPF (11 digits) or CNPJ (14 chars). Supports the alphanumeric CNPJ format introduced by the
 * Receita Federal (12 alphanumeric positions + 2 numeric check digits).
 */
public record TaxId(String value, DocumentType type) {

    public enum DocumentType { CPF, CNPJ }

    private static final int[] CNPJ_W1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] CNPJ_W2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public static TaxId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        String s = raw.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
        if (s.matches("[0-9]{11}") && validCpf(s)) {
            return new TaxId(s, DocumentType.CPF);
        }
        if (s.matches("[0-9A-Z]{12}[0-9]{2}") && validCnpj(s)) {
            return new TaxId(s, DocumentType.CNPJ);
        }
        throw invalid();
    }

    /** Safe to log: only the last four characters are kept. */
    public String masked() {
        return "***" + value.substring(value.length() - 4);
    }

    private static ValidationException invalid() {
        return new ValidationException("INVALID_TAX_ID", "taxId is not a valid CPF or CNPJ");
    }

    private static boolean validCpf(String s) {
        if (s.chars().distinct().count() == 1) {
            return false;
        }
        return cpfDigit(s, 9, 10) == s.charAt(9) - '0' && cpfDigit(s, 10, 11) == s.charAt(10) - '0';
    }

    private static int cpfDigit(String s, int length, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (s.charAt(i) - '0') * (firstWeight - i);
        }
        int r = (sum * 10) % 11;
        return r == 10 ? 0 : r;
    }

    private static boolean validCnpj(String s) {
        if (s.chars().distinct().count() == 1) {
            return false;
        }
        return cnpjDigit(s, CNPJ_W1) == s.charAt(12) - '0' && cnpjDigit(s, CNPJ_W2) == s.charAt(13) - '0';
    }

    private static int cnpjDigit(String s, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (s.charAt(i) - '0') * weights[i]; // ASCII value - 48, as defined for alphanumeric CNPJ
        }
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }
}

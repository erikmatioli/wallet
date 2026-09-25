package br.com.walletcore.domain.exception;

/** Base type for every business/validation failure. Carries a stable, machine-readable code. */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

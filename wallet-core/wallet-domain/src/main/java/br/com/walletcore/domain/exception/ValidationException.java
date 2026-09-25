package br.com.walletcore.domain.exception;

/** Signals malformed or invalid input. */
public class ValidationException extends DomainException {

    public ValidationException(String code, String message) {
        super(code, message);
    }
}

package br.com.walletcore.domain.exception;

/** Signals a request that conflicts with the current state. */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}

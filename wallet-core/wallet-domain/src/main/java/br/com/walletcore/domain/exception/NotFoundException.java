package br.com.walletcore.domain.exception;

/** Signals a resource that does not exist or is not visible to the tenant. */
public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}

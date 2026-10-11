package br.com.walletcore.domain.exception;

/** The caller may use the endpoint, but not for this: the fraud scope cannot lift a judicial block. */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String code, String message) {
        super(code, message);
    }
}

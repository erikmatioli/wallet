package br.com.walletcore.domain.exception;

/** Signals a well-formed request that violates a business rule. */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }
}

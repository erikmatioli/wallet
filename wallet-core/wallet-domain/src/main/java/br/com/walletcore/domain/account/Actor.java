package br.com.walletcore.domain.account;

import br.com.walletcore.domain.exception.ValidationException;
import java.util.Objects;

/**
 * Who did it (ADR-013, item 7): the operator's name as the console declared it, next to the client id of the
 * token that called. The console has no operator login yet, so {@code operator} is declared, not authenticated.
 */
public record Actor(String operator, String clientId) {

    static final int MAX_OPERATOR = 80;

    public Actor {
        Objects.requireNonNull(clientId, "clientId");
        operator = operator == null ? "" : operator.strip();
        if (operator.isEmpty() || operator.length() > MAX_OPERATOR) {
            throw new ValidationException("INVALID_OPERATOR", "operator is required, at most 80 characters");
        }
    }

    /** What the core records by itself, on behalf of the calling client: the opening of an account. */
    public static Actor system(String clientId) {
        return new Actor("sistema", clientId);
    }
}

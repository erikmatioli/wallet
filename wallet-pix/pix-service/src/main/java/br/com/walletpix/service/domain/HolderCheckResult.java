package br.com.walletpix.service.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Answer of wallet-core's {@code POST /v1/accounts/holder-check}, and the rejection it implies.
 * {@code accountId} is present only when {@code outcome} is {@link Outcome#VALID}.
 */
public record HolderCheckResult(Outcome outcome, UUID accountId) {

    /** Mirrors wallet-core's HolderCheck.Result names one to one. */
    public enum Outcome { VALID, ACCOUNT_NOT_FOUND, ACCOUNT_BLOCKED, ACCOUNT_CLOSED, TAX_ID_MISMATCH }

    public Optional<RejectionReason> rejectionReason() {
        return switch (outcome) {
            case VALID -> Optional.empty();
            case ACCOUNT_NOT_FOUND -> Optional.of(RejectionReason.AC03);
            case ACCOUNT_BLOCKED -> Optional.of(RejectionReason.AC06);
            case ACCOUNT_CLOSED -> Optional.of(RejectionReason.AC07);
            case TAX_ID_MISMATCH -> Optional.of(RejectionReason.BE01);
        };
    }
}

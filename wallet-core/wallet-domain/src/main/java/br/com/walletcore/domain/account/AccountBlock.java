package br.com.walletcore.domain.account;

import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * A block on an account (ADR-013). An account may have several at once, each released on its own by whoever may
 * release it; a released block is kept, with its release. What the account may do is their sum, see
 * {@link AccountPolicy}.
 */
public record AccountBlock(
        BlockId id,
        TenantId tenantId,
        AccountId accountId,
        Type type,
        Reason reason,
        String note,
        CourtOrder court,
        Actor createdBy,
        Instant createdAt,
        Release release) {

    static final int MAX_NOTE = 280;

    public enum Type {
        /** Receives; the only way out is the MED (PIX_RETURN_OUT). Created and released with accounts:block. */
        FRAUD,
        /** Receives nothing but refunds; nothing leaves. Created and released with accounts:block. */
        ADMINISTRATIVE,
        /** Receives; the only way out is the judicial transfer. Created and released with accounts:judicial. */
        JUDICIAL;

        /** The scope that creates and releases a block of this type. */
        public String scope() {
            return this == JUDICIAL ? "accounts:judicial" : "accounts:block";
        }
    }

    public enum Reason { FRAUD_SUSPICION, CUSTOMER_REQUEST, REGISTRATION_PENDING, INSTITUTION_DECISION, COURT_ORDER, OTHER }

    /** The court's order: the process number (required) and the order's protocol (the SISBAJUD one, say). */
    public record CourtOrder(String process, String protocol) {

        public CourtOrder {
            process = trim(process, 60, "INVALID_COURT_PROCESS", "court process");
            protocol = trim(protocol, 60, "INVALID_COURT_PROTOCOL", "court protocol");
            if (process == null) {
                throw new ValidationException("COURT_PROCESS_REQUIRED", "a judicial block needs the court process number");
            }
        }
    }

    public record Release(Actor by, Instant at, String note) {

        public Release {
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
            note = trim(note, MAX_NOTE, "INVALID_NOTE", "note");
            if (note == null) {
                throw new ValidationException("RELEASE_NOTE_REQUIRED", "say why the block is released");
            }
        }
    }

    public AccountBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (type == null) {
            throw new ValidationException("INVALID_BLOCK_TYPE", "type must be FRAUD, ADMINISTRATIVE or JUDICIAL");
        }
        if (reason == null) {
            throw new ValidationException("INVALID_BLOCK_REASON", "reason is required");
        }
        note = trim(note, MAX_NOTE, "INVALID_NOTE", "note");
        if ((type == Type.JUDICIAL) != (reason == Reason.COURT_ORDER)) {
            throw new ValidationException("INVALID_BLOCK_REASON", "a judicial block is a court order, and only it is");
        }
        if (type == Type.JUDICIAL && court == null) {
            throw new ValidationException("COURT_PROCESS_REQUIRED", "a judicial block needs the court process number");
        }
        if (type != Type.JUDICIAL && court != null) {
            throw new ValidationException("INVALID_BLOCK", "only a judicial block carries a court order");
        }
        if (reason == Reason.OTHER && note == null) {
            throw new ValidationException("NOTE_REQUIRED", "a block for another reason needs a note");
        }
    }

    public static AccountBlock create(TenantId tenantId, AccountId accountId, Type type, Reason reason, String note,
                                      CourtOrder court, Actor by, Instant now) {
        return new AccountBlock(BlockId.newId(), tenantId, accountId, type, reason, note, court, by, now, null);
    }

    public boolean isActive() {
        return release == null;
    }

    public AccountBlock release(Actor by, String note, Instant now) {
        if (!isActive()) {
            throw new BusinessRuleException("BLOCK_ALREADY_RELEASED", "this block was already released");
        }
        return new AccountBlock(id, tenantId, accountId, type, reason, this.note, court, createdBy, createdAt,
                new Release(by, now, note));
    }

    private static String trim(String value, int max, String code, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new ValidationException(code, what + " must have at most " + max + " characters");
        }
        return v;
    }
}

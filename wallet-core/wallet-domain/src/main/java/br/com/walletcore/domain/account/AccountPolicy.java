package br.com.walletcore.domain.account;

import br.com.walletcore.domain.ledger.TransactionType;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * What an account may do now, summed up from its active blocks (ADR-013, item 2). The ledger reads these two
 * columns in its single balance UPDATE instead of the block table; they are rewritten in the same transaction
 * that creates or releases a block.
 */
public record AccountPolicy(Credit credit, Debit debit) {

    public enum Credit {
        ALLOWED,
        /** An ADMINISTRATIVE block: only what undoes a debit (a Pix refund or return, a withdrawal reversal) comes in. */
        REFUND_ONLY
    }

    public enum Debit {
        ALLOWED,
        /** Only FRAUD blocks: the only way out is the return of a received Pix (MED). */
        MED_ONLY,
        /** A JUDICIAL block: the only way out is the judicial transfer. */
        JUDICIAL_ONLY,
        /** An ADMINISTRATIVE block: nothing leaves, but a judicial transfer still does. */
        NONE
    }

    public static final AccountPolicy OPEN = new AccountPolicy(Credit.ALLOWED, Debit.ALLOWED);

    /** A movement passes only if every active block allows it; the strictest block decides each direction. */
    public static AccountPolicy of(Collection<AccountBlock.Type> active) {
        boolean administrative = active.contains(AccountBlock.Type.ADMINISTRATIVE);
        Credit credit = administrative ? Credit.REFUND_ONLY : Credit.ALLOWED;
        Debit debit;
        if (administrative) {
            debit = Debit.NONE;
        } else if (active.contains(AccountBlock.Type.JUDICIAL)) {
            debit = Debit.JUDICIAL_ONLY; // also with FRAUD: the MED would take out money the court holds
        } else if (active.contains(AccountBlock.Type.FRAUD)) {
            debit = Debit.MED_ONLY;
        } else {
            debit = Debit.ALLOWED;
        }
        return new AccountPolicy(credit, debit);
    }

    public boolean canReceive() {
        return credit == Credit.ALLOWED;
    }

    public boolean canPay() {
        return debit == Debit.ALLOWED;
    }

    /**
     * The policies under which a customer leg of this movement passes.
     *
     * @param refund the credit undoes a debit of the same account (a withdrawal reversal): it comes in under any
     *               block, or the customer's money would be lost in the tenant. A Pix refund and the return of a sent
     *               Pix are the same, and say so by their type
     */
    public static Gate gateFor(TransactionType type, boolean refund) {
        boolean undoesADebit = refund || type == TransactionType.PIX_REFUND || type == TransactionType.PIX_RETURN_IN;
        Set<Credit> credits = undoesADebit ? EnumSet.allOf(Credit.class) : EnumSet.of(Credit.ALLOWED);
        Set<Debit> debits = switch (type) {
            case PIX_RETURN_OUT -> EnumSet.of(Debit.ALLOWED, Debit.MED_ONLY);
            // The court's order prevails over the fraud and administrative blocks; only a closed account stops it.
            case JUDICIAL_TRANSFER -> EnumSet.allOf(Debit.class);
            default -> EnumSet.of(Debit.ALLOWED);
        };
        return new Gate(credits, debits);
    }

    /** What the balance UPDATE accepts: the credit policies for a credit, the debit policies for a debit. */
    public record Gate(Set<Credit> credits, Set<Debit> debits) {

        /** Internal accounts (backing, judicial transit) are never blocked. */
        public static final Gate ANY = new Gate(EnumSet.allOf(Credit.class), EnumSet.allOf(Debit.class));

        public Gate {
            credits = Set.copyOf(credits);
            debits = Set.copyOf(debits);
        }

        public boolean admits(AccountPolicy policy, long deltaCents) {
            return deltaCents > 0 ? credits.contains(policy.credit()) : debits.contains(policy.debit());
        }
    }
}

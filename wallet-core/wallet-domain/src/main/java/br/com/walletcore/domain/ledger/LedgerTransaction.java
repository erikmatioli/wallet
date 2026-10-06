package br.com.walletcore.domain.ledger;

import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A balanced double-entry posting (partidas dobradas): total debits always equal total credits.
 * The invariant is enforced on construction, so an unbalanced transaction cannot exist in memory;
 * the database re-checks it at commit time as a second line of defence.
 */
public record LedgerTransaction(
        TransactionId id,
        TenantId tenantId,
        TransactionType type,
        Money amount,
        String description,
        List<Leg> legs,
        Instant occurredAt) {

    private static final int MAX_DESCRIPTION = 140;

    public LedgerTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");
        description = normalize(description);
        legs = List.copyOf(legs);

        if (!amount.isPositive()) {
            throw new ValidationException("INVALID_AMOUNT", "amount must be greater than zero");
        }
        if (legs.size() < 2) {
            throw new BusinessRuleException("INVALID_TRANSACTION", "a transaction requires at least two legs");
        }
        if (legs.stream().map(Leg::accountId).distinct().count() != legs.size()) {
            throw new BusinessRuleException("SAME_ACCOUNT", "source and destination must be different accounts");
        }
        long debits = total(legs, EntryDirection.DEBIT);
        long credits = total(legs, EntryDirection.CREDIT);
        if (debits != credits) {
            throw new BusinessRuleException("UNBALANCED_TRANSACTION", "total debits must equal total credits");
        }
        if (debits != amount.cents()) {
            throw new BusinessRuleException("INVALID_TRANSACTION", "legs do not add up to the transaction amount");
        }
    }

    /** Money enters the platform: settlement is debited, the customer account is credited. */
    public static LedgerTransaction deposit(TenantId tenantId, TransactionId id, AccountId customerAccount,
                                            AccountId settlementAccount, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.DEPOSIT, amount, description,
                List.of(new Leg(settlementAccount, EntryDirection.DEBIT, amount),
                        new Leg(customerAccount, EntryDirection.CREDIT, amount)), now);
    }

    /** Money leaves the platform: the customer account is debited, settlement is credited. */
    public static LedgerTransaction withdrawal(TenantId tenantId, TransactionId id, AccountId customerAccount,
                                               AccountId settlementAccount, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.WITHDRAWAL, amount, description,
                List.of(new Leg(customerAccount, EntryDirection.DEBIT, amount),
                        new Leg(settlementAccount, EntryDirection.CREDIT, amount)), now);
    }

    /**
     * A Pix movement: the customer account against settlement, in the direction the type implies
     * (see {@link TransactionType#creditsCustomer()}). The rules that tie a refund or return to
     * the original Pix live in the application service, which can see the other transactions.
     */
    public static LedgerTransaction pix(TenantId tenantId, TransactionId id, TransactionType type,
                                        AccountId customerAccount, AccountId settlementAccount, Money amount,
                                        String description, Instant now) {
        Objects.requireNonNull(type, "type");
        if (!type.isPix()) {
            throw new IllegalArgumentException(type + " is not a Pix transaction type");
        }
        List<Leg> legs = type.creditsCustomer()
                ? List.of(new Leg(settlementAccount, EntryDirection.DEBIT, amount),
                        new Leg(customerAccount, EntryDirection.CREDIT, amount))
                : List.of(new Leg(customerAccount, EntryDirection.DEBIT, amount),
                        new Leg(settlementAccount, EntryDirection.CREDIT, amount));
        return new LedgerTransaction(id, tenantId, type, amount, description, legs, now);
    }

    /** Book transfer between two accounts of the same tenant. */
    public static LedgerTransaction transfer(TenantId tenantId, TransactionId id, AccountId source,
                                             AccountId destination, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.TRANSFER, amount, description,
                List.of(new Leg(source, EntryDirection.DEBIT, amount),
                        new Leg(destination, EntryDirection.CREDIT, amount)), now);
    }

    /**
     * Legs sorted by account id. Locking accounts in this deterministic order in every
     * transaction makes deadlocks impossible (A->B and B->A both lock the lower id first).
     */
    public List<Leg> legsInLockOrder() {
        return legs.stream().sorted(Comparator.comparing(l -> l.accountId().value())).toList();
    }

    private static long total(List<Leg> legs, EntryDirection direction) {
        return legs.stream().filter(l -> l.direction() == direction).mapToLong(l -> l.amount().cents()).sum();
    }

    private static String normalize(String description) {
        String d = description == null ? "" : description.strip();
        if (d.length() > MAX_DESCRIPTION) {
            throw new ValidationException("INVALID_DESCRIPTION", "description must have at most 140 characters");
        }
        return d;
    }
}

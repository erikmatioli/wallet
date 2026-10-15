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

    /** Money enters the platform: the backing account is debited, the customer account is credited. */
    public static LedgerTransaction deposit(TenantId tenantId, TransactionId id, AccountId customerAccount,
                                            AccountId settlementAccount, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.DEPOSIT, amount, description,
                List.of(new Leg(settlementAccount, EntryDirection.DEBIT, amount),
                        new Leg(customerAccount, EntryDirection.CREDIT, amount)), now);
    }

    /** Money leaves the platform: the customer account is debited, the backing account is credited. */
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
     * The treasury moves money from one backing account to another (ADR-011). A backing account's balance is
     * negative for the money held there, so the money leaving {@code from} is a credit (its expected balance goes
     * down) and the money arriving at {@code to} is a debit (its expected balance goes up).
     */
    public static LedgerTransaction treasuryTransfer(TenantId tenantId, TransactionId id, AccountId fromShard,
                                                     AccountId toShard, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.TREASURY_TRANSFER, amount, description,
                List.of(new Leg(fromShard, EntryDirection.CREDIT, amount),
                        new Leg(toShard, EntryDirection.DEBIT, amount)), now);
    }

    /**
     * A court's order carried out (ADR-013): the blocked account is debited and {@code destination} (another customer
     * account, or the judicial transit) is credited.
     */
    public static LedgerTransaction judicialTransfer(TenantId tenantId, TransactionId id, AccountId blockedAccount,
                                                     AccountId destination, Money amount, String description,
                                                     Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.JUDICIAL_TRANSFER, amount, description,
                List.of(new Leg(blockedAccount, EntryDirection.DEBIT, amount),
                        new Leg(destination, EntryDirection.CREDIT, amount)), now);
    }

    /**
     * The TED of a judicial transfer left the tenant: the judicial transit is debited and the backing account the TED
     * was sent from is credited, as a withdrawal credits the cash.
     */
    public static LedgerTransaction judicialTransitOut(TenantId tenantId, TransactionId id, AccountId transit,
                                                       AccountId backingShard, Money amount, String description,
                                                       Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.JUDICIAL_TRANSIT_OUT, amount, description,
                List.of(new Leg(transit, EntryDirection.DEBIT, amount),
                        new Leg(backingShard, EntryDirection.CREDIT, amount)), now);
    }

    /** A fee (ADR-014): the customer is debited, the tenant's revenue account is credited. */
    public static LedgerTransaction fee(TenantId tenantId, TransactionId id, AccountId customerAccount,
                                        AccountId revenueAccount, Money amount, String description, Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.FEE, amount, description,
                List.of(new Leg(customerAccount, EntryDirection.DEBIT, amount),
                        new Leg(revenueAccount, EntryDirection.CREDIT, amount)), now);
    }

    /** A fee given back: the revenue account is debited, the customer is credited. */
    public static LedgerTransaction feeRefund(TenantId tenantId, TransactionId id, AccountId customerAccount,
                                              AccountId revenueAccount, Money amount, String description,
                                              Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.FEE_REFUND, amount, description,
                List.of(new Leg(revenueAccount, EntryDirection.DEBIT, amount),
                        new Leg(customerAccount, EntryDirection.CREDIT, amount)), now);
    }

    /**
     * The tenant's revenue left the platform (ADR-015): the revenue account is debited and the backing account the
     * money left from is credited, as the TED of a judicial transfer credits it.
     */
    public static LedgerTransaction revenueWithdrawal(TenantId tenantId, TransactionId id, AccountId revenueAccount,
                                                      AccountId backingShard, Money amount, String description,
                                                      Instant now) {
        return new LedgerTransaction(id, tenantId, TransactionType.REVENUE_WITHDRAWAL, amount, description,
                List.of(new Leg(revenueAccount, EntryDirection.DEBIT, amount),
                        new Leg(backingShard, EntryDirection.CREDIT, amount)), now);
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

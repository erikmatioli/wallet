package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.LocalDate;
import java.util.List;

/**
 * The treasury's view of the tenant (ADR-011): where the money that backs the customers should be, and how much
 * of it a deposit created and the treasury has not covered yet. Amounts are the "expected balance": what the tenant
 * should have in that place, positive (the ledger keeps them with the settlement's sign, negative). Moving money
 * between backing accounts is {@link MoveMoneyUseCase#treasuryTransfer}.
 */
public interface TreasuryUseCase {

    int MAX_PAGE = 200;

    /**
     * @param expectedYesterday at the end of yesterday; null in the first minute after midnight, while yesterday
     *                          may still have entries committing (ADR-012's grace)
     */
    record Position(BackingAccount account, Money expected, Money expectedYesterday) {
    }

    /** Every backing account with its expected balance now and at the end of yesterday. */
    List<Position> positions(TenantId tenantId);

    record RegisterCommand(TenantId tenantId, BackingType type, String name, String externalRef) {
    }

    /** Registers a place the treasury keeps money in (Reservas, CCME, an investment account, the credit portfolio). */
    BackingAccount register(RegisterCommand command);

    record DayPosition(LocalDate date, Money expected) {
    }

    /** The expected balance at the end of each day from {@code from} to {@code to} (closed days, at most 366). */
    List<DayPosition> dailyPositions(TenantId tenantId, BackingAccountId id, LocalDate from, LocalDate to);

    /**
     * One movement of a backing account, merged across its shards.
     *
     * @param expectedAfter      the backing account's expected balance right after this entry
     * @param customerName       the customer on the other side, for a customer movement
     * @param customerAccount    that customer's account, formatted
     * @param otherBackingAccount the backing account on the other side, for a treasury transfer
     * @param reference          the treasury transfer's receipt and note
     */
    record Entry(LedgerEntry entry, Money expectedAfter, String customerName, String customerAccount,
                 BackingAccount otherBackingAccount, TreasuryReference reference) {

        /** Money arrived at this place (a debit of the backing account) or left it. */
        public boolean in() {
            return entry.signedCents() < 0;
        }
    }

    /** @param next the cursor of the next (older) page; null when there is none */
    record Statement(BackingAccount account, List<Entry> entries, String next) {
    }

    /** The movements of a backing account, newest first. {@code cursor} null starts from the newest. */
    Statement statement(TenantId tenantId, BackingAccountId id, String cursor, int limit);

    /**
     * The uncovered money of one day: what deposits created, what the treasury covered, and what was still
     * uncovered when the day ended.
     */
    record UncoveredDay(LocalDate date, Money created, Money covered, Money uncoveredAtEnd) {
    }

    List<UncoveredDay> uncoveredDaily(TenantId tenantId, LocalDate from, LocalDate to);

    /**
     * The tenant's revenue account (ADR-014): what the fees took in, net of the refunds, and its movements, newest
     * first. {@code before} is the sequence cursor of the previous page; {@code next} that of the next one.
     */
    record Revenue(Money balance, List<LedgerEntry> entries, Long next) {
    }

    Revenue revenue(TenantId tenantId, Long before, int limit);
}

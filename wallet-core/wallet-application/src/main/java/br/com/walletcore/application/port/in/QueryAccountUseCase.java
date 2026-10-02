package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;

public interface QueryAccountUseCase {

    /** Account data including its current balance. */
    Account getAccount(TenantId tenantId, AccountId accountId);

    /** Same as {@link #getAccount}, plus the owning customer's display name and masked document. */
    AccountDetail getAccountDetail(TenantId tenantId, AccountId accountId);

    record AccountDetail(Account account, String customerName, String documentMasked) {
    }

    /** Same as {@link #getAccount}, addressed by the bank-style number instead of the internal id. */
    Account getAccountByNumber(TenantId tenantId, String branch, String number, String checkDigit);

    /**
     * Statement, newest first, using keyset pagination on the per-account sequence.
     *
     * @param beforeSequence only entries with a smaller sequence are returned (null = from the newest)
     */
    Statement getStatement(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit);

    /**
     * A ledger entry enriched for display: for a TRANSFER, who the counterparty is (customer
     * name + formatted account number), fetched in one batched query per page rather than one
     * query per row - see QueryAccountService.getStatement. Null for every other transaction
     * type, by the same invariant as {@link LedgerEntry#counterpartyAccountId()}: a deposit or
     * withdrawal's counterparty is an internal settlement account, which never surfaces here.
     */
    record StatementEntry(LedgerEntry entry, String counterpartyCustomerName, String counterpartyAccountFormatted) {
    }

    /** {@code nextBefore} is the cursor for the next page, or null when there are no more entries. */
    record Statement(List<StatementEntry> entries, Long nextBefore) {
    }
}

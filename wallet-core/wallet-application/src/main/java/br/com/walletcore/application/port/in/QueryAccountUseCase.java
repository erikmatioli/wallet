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

    /** Same as {@link #getAccount}, addressed by the bank-style number instead of the internal id. */
    Account getAccountByTaxId(TenantId tenantId, String rawTaxId);

    /**
     * Whether branch + number + check digit identify a CUSTOMER account of this tenant that can
     * receive money and whose holder has the given CPF/CNPJ - the check a receiving PSP makes
     * before accepting a Pix (pacs.008). A negative outcome is a normal answer, not an exception:
     * the caller maps it to a rejection reason. A malformed tax id counts as
     * {@link HolderCheck.Result#TAX_ID_MISMATCH} (no holder can have an invalid document).
     */
    HolderCheck checkHolder(TenantId tenantId, String branch, String number, String checkDigit, String rawTaxId);

    /** {@code accountId} is only set when {@code result} is {@link Result#VALID}, so nothing leaks on a miss. */
    record HolderCheck(Result result, AccountId accountId) {

        public enum Result { VALID, ACCOUNT_NOT_FOUND, ACCOUNT_BLOCKED, ACCOUNT_CLOSED, TAX_ID_MISMATCH }

        public static HolderCheck valid(AccountId accountId) {
            return new HolderCheck(Result.VALID, accountId);
        }

        public static HolderCheck rejected(Result result) {
            return new HolderCheck(result, null);
        }
    }

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

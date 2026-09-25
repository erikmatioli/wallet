package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;

public interface QueryAccountUseCase {

    /** Account data including its current balance. */
    Account getAccount(TenantId tenantId, AccountId accountId);

    /**
     * Statement, newest first, using keyset pagination on the per-account sequence.
     *
     * @param beforeSequence only entries with a smaller sequence are returned (null = from the newest)
     */
    Statement getStatement(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit);

    /** {@code nextBefore} is the cursor for the next page, or null when there are no more entries. */
    record Statement(List<LedgerEntry> entries, Long nextBefore) {
    }
}

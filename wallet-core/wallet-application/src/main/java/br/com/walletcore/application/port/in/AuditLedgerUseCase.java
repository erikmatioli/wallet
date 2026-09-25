package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;

/** Rebuilds an account balance from its ledger events and compares it with the stored projection. */
public interface AuditLedgerUseCase {

    AuditReport audit(TenantId tenantId, AccountId accountId);

    record AuditReport(AccountId accountId, long entryCount, Money storedBalance, Money replayedBalance,
                       long storedVersion, boolean consistent, List<String> findings) {
    }
}

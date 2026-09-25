package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;
import java.util.function.Consumer;

/** Append-only. There is intentionally no update or delete operation. */
public interface LedgerRepository {

    void append(List<LedgerEntry> entries);

    /** Newest first. */
    List<LedgerEntry> findPage(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit);

    /** Visits every entry of the account in ascending sequence order (bounded memory). */
    void forEachInSequence(TenantId tenantId, AccountId accountId, Consumer<LedgerEntry> consumer);
}

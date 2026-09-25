package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;

public final class QueryAccountService implements QueryAccountUseCase {

    private static final int MAX_PAGE = 200;

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public QueryAccountService(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger) {
        this.tx = tx;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Override
    public Account getAccount(TenantId tenantId, AccountId accountId) {
        return tx.readOnly(tenantId, () -> load(tenantId, accountId));
    }

    @Override
    public Statement getStatement(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE);
        return tx.readOnly(tenantId, () -> {
            load(tenantId, accountId);
            List<LedgerEntry> page = ledger.findPage(tenantId, accountId, beforeSequence, pageSize);
            Long next = page.size() == pageSize ? page.get(page.size() - 1).sequence() : null;
            return new Statement(page, next);
        });
    }

    private Account load(TenantId tenantId, AccountId accountId) {
        return accounts.findById(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }
}

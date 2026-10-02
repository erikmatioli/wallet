package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.ListAccountsUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;

public final class ListAccountsService implements ListAccountsUseCase {

    private static final int MAX_PAGE = 200;
    private static final int DEFAULT_PAGE = 20;

    private final TransactionRunner tx;
    private final AccountRepository accounts;

    public ListAccountsService(TransactionRunner tx, AccountRepository accounts) {
        this.tx = tx;
        this.accounts = accounts;
    }

    @Override
    public Page list(TenantId tenantId, AccountId cursor, int limit) {
        int pageSize = limit <= 0 ? DEFAULT_PAGE : Math.min(limit, MAX_PAGE);
        return tx.readOnly(tenantId, () -> {
            List<AccountDirectoryItem> rows = accounts.findAccountDirectory(tenantId, cursor, pageSize);
            List<Item> items = rows.stream()
                    .map(row -> ListAccountsUseCase.toItem(row.account(), row.customerName(), row.documentMasked()))
                    .toList();
            AccountId next = rows.size() == pageSize ? rows.get(rows.size() - 1).account().id() : null;
            return new Page(items, next);
        });
    }
}

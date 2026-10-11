package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.BalanceHistoryUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.time.LocalDate;
import java.util.List;

/**
 * Closing balances of a customer account, read from the ledger when asked: nothing is stored (ADR-012). The binary
 * search over the account's sequence is {@link ClosingBalances}.
 */
public final class BalanceHistoryService implements BalanceHistoryUseCase {

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final ClosingBalances closings;

    public BalanceHistoryService(TransactionRunner tx, AccountRepository accounts, ClosingBalances closings) {
        this.tx = tx;
        this.accounts = accounts;
        this.closings = closings;
    }

    @Override
    public ClosingBalance closingBalance(TenantId tenantId, AccountId accountId, LocalDate date) {
        closings.requireClosed(date);
        return tx.readOnly(tenantId, () -> {
            Account account = load(tenantId, accountId);
            return closings.closing(tenantId, accountId, date, account.version());
        });
    }

    @Override
    public List<ClosingBalance> dailyBalances(TenantId tenantId, AccountId accountId, LocalDate from, LocalDate to) {
        closings.requirePeriod(from, to);
        return tx.readOnly(tenantId, () -> {
            Account account = load(tenantId, accountId);
            return closings.daily(tenantId, accountId, from, to, account.version());
        });
    }

    private Account load(TenantId tenantId, AccountId accountId) {
        return accounts.findById(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }
}

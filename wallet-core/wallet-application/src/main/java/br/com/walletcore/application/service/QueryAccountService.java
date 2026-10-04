package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    public AccountDetail getAccountDetail(TenantId tenantId, AccountId accountId) {
        // Reuses the same batched read-model lookup built for statement counterparty enrichment
        // (and the accounts directory) - a single-id set is just the smallest possible batch.
        return tx.readOnly(tenantId, () -> accounts.findByIds(tenantId, Set.of(accountId)).stream()
                .findFirst()
                .map(item -> new AccountDetail(item.account(), item.customerName(), item.documentMasked()))
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found")));
    }

    @Override
    public Account getAccountByNumber(TenantId tenantId, String branch, String number, String checkDigit) {
        return tx.readOnly(tenantId, () -> accounts.findByNumber(tenantId, branch, number, checkDigit)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found")));
    }

    @Override
    public Account getAccountByTaxId(TenantId tenantId, String rawTaxId) {
        TaxId taxId = TaxId.parse(rawTaxId); // transforma a string e valida para formar o objeto
        return tx.readOnly(tenantId, () -> accounts.findByTaxId(tenantId, taxId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found")));
    }

    @Override
    public Statement getStatement(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE);
        return tx.readOnly(tenantId, () -> {
            load(tenantId, accountId);
            List<LedgerEntry> page = ledger.findPage(tenantId, accountId, beforeSequence, pageSize);
            Long next = page.size() == pageSize ? page.get(page.size() - 1).sequence() : null;
            Map<AccountId, AccountDirectoryItem> counterparties = loadCounterparties(tenantId, page);
            List<StatementEntry> enriched = page.stream().map(e -> enrich(e, counterparties)).toList();
            return new Statement(enriched, next);
        });
    }

    /** One batched lookup for the whole page, instead of one query per row with a counterparty. */
    private Map<AccountId, AccountDirectoryItem> loadCounterparties(TenantId tenantId, List<LedgerEntry> page) {
        Set<AccountId> ids = page.stream()
                .filter(e -> e.type() == TransactionType.TRANSFER && e.counterpartyAccountId() != null)
                .map(LedgerEntry::counterpartyAccountId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accounts.findByIds(tenantId, ids).stream()
                .collect(Collectors.toMap(item -> item.account().id(), Function.identity()));
    }

    private static StatementEntry enrich(LedgerEntry e, Map<AccountId, AccountDirectoryItem> counterparties) {
        if (e.type() != TransactionType.TRANSFER || e.counterpartyAccountId() == null) {
            return new StatementEntry(e, null, null);
        }
        AccountDirectoryItem counterparty = counterparties.get(e.counterpartyAccountId());
        if (counterparty == null) {
            // Shouldn't happen for a genuine transfer counterparty - degrade gracefully rather
            // than fail the whole statement over a display-only enrichment.
            return new StatementEntry(e, null, null);
        }
        return new StatementEntry(e, counterparty.customerName(), counterparty.account().number().formatted());
    }

    private Account load(TenantId tenantId, AccountId accountId) {
        return accounts.findById(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }
}

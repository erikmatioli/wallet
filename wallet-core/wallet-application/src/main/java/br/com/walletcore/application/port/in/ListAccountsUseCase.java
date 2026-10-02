package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.util.List;

/** Backs the accounts directory (admin-style "list every account of this tenant") screen. */
public interface ListAccountsUseCase {

    Page list(TenantId tenantId, AccountId cursor, int limit);

    record Item(AccountId accountId, String customerName, String documentMasked, String accountFormatted,
               String accountType, String status, Money balance, String currency, Instant createdAt) {
    }

    /** {@code nextCursor} is null when this was the last page. */
    record Page(List<Item> items, AccountId nextCursor) {
    }

    static Item toItem(Account account, String customerName, String documentMasked) {
        return new Item(account.id(), customerName, documentMasked, account.number().formatted(),
                account.number().type().bcbCode(), account.status().name(), account.balance(), Money.CURRENCY,
                account.createdAt());
    }
}

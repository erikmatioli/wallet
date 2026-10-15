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

    /**
     * {@code canReceive} and {@code canPay}: what the account's active blocks let it do (ADR-013). {@code document}:
     * the holder's full CPF/CNPJ, for the operator; the API shows it only to a token with customers:document.
     */
    record Item(AccountId accountId, String customerName, String documentMasked, String accountFormatted,
               String accountType, String status, Money balance, String currency, Instant createdAt,
               boolean canReceive, boolean canPay, String documentType, String document) {
    }

    /** {@code nextCursor} is null when this was the last page. */
    record Page(List<Item> items, AccountId nextCursor) {
    }

    static Item toItem(Account account, String customerName, String documentMasked) {
        return toItem(account, customerName, documentMasked, null);
    }

    static Item toItem(Account account, String customerName, String documentMasked, String documentType) {
        return toItem(account, customerName, documentMasked, documentType, null);
    }

    static Item toItem(Account account, String customerName, String documentMasked, String documentType,
                       String document) {
        return new Item(account.id(), customerName, documentMasked, account.number().formatted(),
                account.number().type().bcbCode(), account.status().name(), account.balance(), Money.CURRENCY,
                account.createdAt(), account.policy().canReceive(), account.policy().canPay(), documentType, document);
    }
}

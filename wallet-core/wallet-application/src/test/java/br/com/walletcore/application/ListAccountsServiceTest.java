package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletcore.application.port.in.ListAccountsUseCase.Item;
import br.com.walletcore.application.port.in.ListAccountsUseCase.Page;
import br.com.walletcore.application.service.ListAccountsService;
import br.com.walletcore.domain.account.Account;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListAccountsServiceTest {

    private final InMemoryFixture f = new InMemoryFixture();
    private final ListAccountsService listAccounts = new ListAccountsService(f.runner, f.accountRepository);

    @Test
    void listsOnlyCustomerAccountsNewestFirst() {
        Account first = f.openCustomerAccount();
        Account second = f.openCustomerAccount();
        Account third = f.openCustomerAccount();

        Page page = listAccounts.list(f.tenant, null, 10);

        // UuidV7 ids are time-ordered, so "newest first" is a plain id sort - no separate
        // created_at cursor needed (see AccountRepository.findAccountDirectory).
        assertThat(page.items()).extracting(Item::accountId)
                .containsExactly(third.id(), second.id(), first.id());
        assertThat(page.nextCursor()).isNull(); // fewer results than the page size requested
        // Settlement accounts (opened by the fixture's constructor) must never leak into the directory.
        assertThat(page.items()).noneMatch(i -> i.accountFormatted() == null);
    }

    @Test
    void paginatesWithACursor() {
        List<Account> created = List.of(f.openCustomerAccount(), f.openCustomerAccount(), f.openCustomerAccount());

        Page firstPage = listAccounts.list(f.tenant, null, 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isEqualTo(firstPage.items().get(1).accountId());

        Page secondPage = listAccounts.list(f.tenant, firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).accountId()).isEqualTo(created.get(0).id());
        assertThat(secondPage.nextCursor()).isNull();
    }
}

package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.StatementPage

/**
 * Reads of the logged-in customer's account. The [AccountId] always comes from the customer's token,
 * never from the request (ADR-001, decision 1): there is no parameter here a customer could change to
 * see someone else's account.
 */
class AccountService(private val core: CoreBanking) {

    fun me(accountId: AccountId): Me = core.me(accountId)

    fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage =
        core.statement(accountId, before, limit.coerceIn(1, MAX_PAGE))

    private companion object {
        const val MAX_PAGE = 50
    }
}

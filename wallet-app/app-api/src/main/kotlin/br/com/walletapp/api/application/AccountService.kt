package br.com.walletapp.api.application

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.StatementPage
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.BalanceHistory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Reads of the logged-in customer's account. The [AccountId] always comes from the customer's token,
 * never from the request (ADR-001, decision 1): there is no parameter here a customer could change to
 * see someone else's account.
 */
class AccountService(private val core: CoreBanking, private val clock: Clock) {

    fun me(accountId: AccountId): Me = core.me(accountId)

    fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage =
        core.statement(accountId, before, limit.coerceIn(1, MAX_PAGE))

    /**
     * The balance at the end of each day. Without a period, the last [DEFAULT_DAYS] closed days. A day is
     * closed some time after midnight (wallet-core's grace, ADR-012); app-api counts a margin larger than it, so
     * it never asks for a day wallet-core would still refuse.
     */
    fun balanceHistory(accountId: AccountId, from: LocalDate?, to: LocalDate?): BalanceHistory {
        val lastClosed = LocalDate.ofInstant(clock.instant().minus(CLOSE_MARGIN), BRASILIA).minusDays(1)
        val end = to ?: lastClosed
        val start = from ?: end.minusDays(DEFAULT_DAYS - 1L)
        if (start.isAfter(end)) throw ValidationException("INVALID_PERIOD", "A data inicial precisa ser antes da final.")
        if (end.isAfter(lastClosed)) {
            throw ValidationException("INVALID_PERIOD", "O saldo de um dia só fecha depois da meia-noite.")
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw ValidationException("INVALID_PERIOD", "Escolha um período de até $MAX_DAYS dias.")
        }
        return BalanceHistory(start.toString(), end.toString(), core.dailyBalances(accountId, start, end))
    }

    private companion object {
        const val MAX_PAGE = 50
        const val DEFAULT_DAYS = 30
        const val MAX_DAYS = 366
        val BRASILIA: ZoneId = ZoneId.of("America/Sao_Paulo")
        val CLOSE_MARGIN: Duration = Duration.ofMinutes(5)
    }
}

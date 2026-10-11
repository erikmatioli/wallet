package br.com.walletapp.api.application

import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class AccountServiceTest {

    private val core = FakeCore()
    private val account = AccountId(UUID.randomUUID())

    /** The service, with its clock at this Brasília wall-clock time. */
    private fun at(brasilia: String) = AccountService(core,
        Clock.fixed(LocalDateTime.parse(brasilia).atZone(ZoneId.of("America/Sao_Paulo")).toInstant(), ZoneOffset.UTC))

    @Test
    fun `without a period, the last 30 closed days`() {
        val history = at("2026-10-10T15:00:00").balanceHistory(account, null, null)

        assertThat(history.from).isEqualTo("2026-09-10")
        assertThat(history.to).isEqualTo("2026-10-09")
        assertThat(history.days).hasSize(30)
        assertThat(core.dailyCalls.single()).isEqualTo(Triple(account, LocalDate.parse("2026-09-10"), LocalDate.parse("2026-10-09")))
    }

    @Test
    fun `right after midnight yesterday is not closed yet - a margin over wallet-core's grace`() {
        assertThat(at("2026-10-10T00:03:00").balanceHistory(account, null, null).to).isEqualTo("2026-10-08")
        assertThat(at("2026-10-10T00:06:00").balanceHistory(account, null, null).to).isEqualTo("2026-10-09")
    }

    @Test
    fun `today, an inverted or a too long period never reach wallet-core`() {
        val service = at("2026-10-10T15:00:00")
        assertThatThrownBy { service.balanceHistory(account, null, LocalDate.parse("2026-10-10")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.balanceHistory(account, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-01")) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service.balanceHistory(account, LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-02")) }
            .isInstanceOf(ValidationException::class.java)
        assertThat(core.dailyCalls).isEmpty()
    }
}

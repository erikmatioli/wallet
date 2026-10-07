package br.com.walletscheduler.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ExecutionWindowsTest {

    private val windows = ExecutionWindows()
    private val day = LocalDate.of(2026, 10, 8)

    @Test
    fun `windows are in Brasilia time whatever the server zone`() {
        // 06:00 in Brasília (UTC-3, no daylight saving since 2019) is 09:00 UTC.
        assertThat(windows.first(day)).isEqualTo(Instant.parse("2026-10-08T09:00:00Z"))
    }

    @Test
    fun `next window is strictly after now and only on the same day`() {
        assertThat(windows.nextAfter(day, Instant.parse("2026-10-08T09:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-08T15:00:00Z")) // 12:00
        assertThat(windows.nextAfter(day, Instant.parse("2026-10-08T21:00:00Z"))).isNull() // after 18:00
    }

    @Test
    fun `today is the calendar day in Brasilia`() {
        // 01:00 UTC on the 9th is still 22:00 on the 8th in Brasília.
        assertThat(windows.today(Instant.parse("2026-10-09T01:00:00Z"))).isEqualTo(day)
    }
}

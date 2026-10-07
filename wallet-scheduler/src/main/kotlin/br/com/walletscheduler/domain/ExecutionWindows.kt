package br.com.walletscheduler.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The times of day a scheduled payment is tried (ADR-001, decision 5): the first window runs it,
 * the next ones retry a refusal that can change within the day. Dates are calendar days in
 * Brasília, whatever the server's time zone.
 */
class ExecutionWindows(
    val zone: ZoneId = ZoneId.of("America/Sao_Paulo"),
    times: List<LocalTime> = listOf(LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0)),
) {
    val times: List<LocalTime> = times.sorted()

    init {
        require(this.times.isNotEmpty()) { "at least one execution window is required" }
    }

    fun today(now: Instant): LocalDate = LocalDate.ofInstant(now, zone)

    fun first(date: LocalDate): Instant = date.atTime(times.first()).atZone(zone).toInstant()

    /** The next window of [date] strictly after [now], or null when the day has no window left. */
    fun nextAfter(date: LocalDate, now: Instant): Instant? =
        times.map { date.atTime(it).atZone(zone).toInstant() }.firstOrNull { it.isAfter(now) }
}

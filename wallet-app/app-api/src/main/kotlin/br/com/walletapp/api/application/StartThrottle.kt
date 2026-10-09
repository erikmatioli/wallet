package br.com.walletapp.api.application

import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.TooManyRequestsException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How often a login code may be asked for a CPF - the same limits as wallet-otp's (ADR-001 of wallet-otp,
 * decision 5), but applied here to **every** CPF, with an account or not. wallet-otp only sees the CPFs
 * that have an account, so its own "wait 60s" would tell them apart; this answers the same way for all.
 *
 * In memory, per instance: a restart forgets it, and wallet-otp's limits still hold behind it.
 */
class StartThrottle(
    private val clock: Clock,
    private val interval: Duration = Duration.ofSeconds(60),
    private val perHour: Int = 5,
) {
    private val window = Duration.ofHours(1)
    private val asked = HashMap<String, ArrayDeque<Instant>>()

    /** Records this request, or refuses it with how long to wait. */
    @Synchronized
    fun check(cpf: Cpf) {
        val now = clock.instant()
        val times = asked.getOrPut(cpf.digits) { ArrayDeque() }
        while (times.isNotEmpty() && !times.first().isAfter(now.minus(window))) times.removeFirst()
        times.lastOrNull()?.let { last ->
            val wait = Duration.between(now, last.plus(interval))
            if (wait > Duration.ZERO) throw tooSoon(wait)
        }
        if (times.size >= perHour) throw tooSoon(Duration.between(now, times.first().plus(window)))
        times.addLast(now)
        if (asked.size > MAX_KEYS) asked.entries.removeIf { (_, t) -> t.lastOrNull()?.isAfter(now.minus(window)) != true }
    }

    private fun tooSoon(wait: Duration): TooManyRequestsException {
        val seconds = ((wait.toMillis() + 999) / 1000).coerceAtLeast(1)
        return TooManyRequestsException(waitMessage(seconds), seconds)
    }

    companion object {
        private const val MAX_KEYS = 10_000

        fun waitMessage(seconds: Long): String =
            if (seconds < 120) "Aguarde $seconds segundos para pedir outro código."
            else "Muitos códigos pedidos. Tente de novo em ${(seconds + 59) / 60} minutos."
    }
}

package br.com.walletotp.domain

import java.time.Duration
import java.time.Instant

/**
 * How often codes may be sent (ADR-001, decision 5), so nobody can flood someone's inbox or spend the
 * email provider. Codes whose email could not be sent (FAILED) do not count: the customer did not get them.
 */
class SendLimits(
    val interval: Duration = Duration.ofSeconds(60),
    val perSubjectPerHour: Int = 5,
    val perDestinationPerHour: Int = 10,
) {
    val window: Duration = Duration.ofHours(1)

    /** The codes counted in the last [window]: how many, and when the oldest of them was sent. */
    data class Window(val count: Int, val oldest: Instant?) {
        companion object {
            val EMPTY = Window(0, null)
        }
    }

    /** What the repository found for this request. */
    data class Recent(val lastForSubject: Instant?, val subject: Window, val destination: Window)

    /** @throws TooManyRequestsException with how long to wait, when a limit is reached */
    fun check(recent: Recent, now: Instant) {
        recent.lastForSubject?.let { last ->
            val wait = Duration.between(now, last.plus(interval))
            if (wait > Duration.ZERO) {
                throw TooManyRequestsException("a code was sent less than ${interval.seconds}s ago", seconds(wait))
            }
        }
        if (recent.subject.count >= perSubjectPerHour) {
            throw TooManyRequestsException("at most $perSubjectPerHour codes per hour for this subject",
                untilFree(recent.subject, now))
        }
        if (recent.destination.count >= perDestinationPerHour) {
            throw TooManyRequestsException("at most $perDestinationPerHour codes per hour for this destination",
                untilFree(recent.destination, now))
        }
    }

    /** The oldest code leaves the window first: that is when one more may be sent. */
    private fun untilFree(w: Window, now: Instant): Long =
        seconds(Duration.between(now, (w.oldest ?: now).plus(window)).coerceAtLeast(Duration.ofSeconds(1)))

    /** Rounded up: "wait 0 seconds" would invite an immediate retry that is still refused. */
    private fun seconds(d: Duration) = (d.toMillis() + 999) / 1000
}

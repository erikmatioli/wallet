package br.com.walletotp.application

import br.com.walletotp.domain.BusinessRuleException
import br.com.walletotp.domain.Challenge
import br.com.walletotp.domain.DomainException
import br.com.walletotp.domain.NotFoundException
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.TenantId
import br.com.walletotp.domain.TooManyRequestsException
import br.com.walletotp.domain.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.UUID

class ChallengeServiceTest {

    private val f = Fixture()

    private fun code(call: ThrowingCallable): String =
        (org.assertj.core.api.Assertions.catchThrowable(call) as DomainException).code

    @Test
    fun `sends a 6-digit code and keeps only its hash and the masked email`() {
        val created = f.create()

        assertThat(f.sender.sent.single().destination).isEqualTo("maria@example.com")
        assertThat(created.destinationMasked).isEqualTo("m***@example.com")
        assertThat(created.expiresAt).isEqualTo(f.clock.now.plusSeconds(300))
        val stored = f.stored(created.id)
        assertThat(stored.codeHash).doesNotContain(f.sender.lastCode()).hasSize(64)
        assertThat(stored.destinationMasked).isEqualTo("m***@example.com")
        assertThat(stored.toString()).doesNotContain("maria@example.com")
    }

    @Test
    fun `the right code verifies once, then it is used`() {
        val created = f.create()
        val code = f.sender.lastCode()

        f.verify(created.id, code)

        assertThat(f.stored(created.id).status).isEqualTo(Challenge.Status.USED)
        assertThat(code { f.verify(created.id, code) }).isEqualTo("CHALLENGE_ALREADY_USED")
    }

    @Test
    fun `wrong codes count down and the fifth one locks the challenge, even for the right code`() {
        val created = f.create()

        val first = catch { f.verify(created.id, "000000") } as BusinessRuleException
        assertThat(first.code).isEqualTo("INVALID_CODE")
        assertThat(first.attemptsLeft).isEqualTo(4)
        repeat(3) { catch { f.verify(created.id, "000000") } }
        assertThat(code { f.verify(created.id, "000000") }).isEqualTo("CHALLENGE_LOCKED")

        assertThat(code { f.verify(created.id, f.sender.lastCode()) }).isEqualTo("CHALLENGE_LOCKED")
        assertThat(f.stored(created.id).status).isEqualTo(Challenge.Status.LOCKED)
    }

    @Test
    fun `a code is valid for 5 minutes`() {
        val created = f.create()
        f.later(299)
        f.verify(created.id, f.sender.lastCode())

        val other = f.create(subject = "11144477735")
        f.later(300)
        assertThat(code { f.verify(other.id, f.sender.lastCode(), subject = "11144477735") }).isEqualTo("CHALLENGE_EXPIRED")
    }

    @Test
    fun `asking for a new code makes the previous one useless`() {
        val first = f.create()
        val firstCode = f.sender.lastCode()
        f.later(60)
        val second = f.create()

        assertThat(code { f.verify(first.id, firstCode) }).isEqualTo("CHALLENGE_SUPERSEDED")
        f.verify(second.id, f.sender.lastCode())
    }

    @Test
    fun `another subject, another tenant or another context never verify, and do not spend attempts`() {
        val created = f.create(purpose = Purpose.SIGNUP, context = "maria@example.com")
        val code = f.sender.lastCode()

        assertThat(code { f.verify(created.id, code, subject = "11144477735", context = "maria@example.com") })
            .isEqualTo("CHALLENGE_NOT_FOUND")
        assertThat(code { f.verify(created.id, code, context = "outra@example.com") }).isEqualTo("CHALLENGE_NOT_FOUND")
        assertThat(code { f.verify(created.id, code) }).isEqualTo("CHALLENGE_NOT_FOUND") // context missing
        assertThat(code { f.verify(created.id, code, context = "maria@example.com", tenantId = TenantId(UUID.randomUUID())) })
            .isEqualTo("CHALLENGE_NOT_FOUND")
        assertThatThrownBy { f.service.verify(ChallengeService.Verify(f.tenant, "not-an-id", "52998224725", code, null)) }
            .isInstanceOf(NotFoundException::class.java)

        assertThat(f.stored(created.id).attempts).isZero()
        f.verify(created.id, code, context = "maria@example.com")
    }

    @Test
    fun `one code a minute and five an hour for a subject, with how long to wait`() {
        f.create()
        f.later(30)
        val tooSoon = catch { f.create() } as TooManyRequestsException
        assertThat(tooSoon.retryAfterSeconds).isEqualTo(30)

        repeat(4) { f.later(60); f.create() } // 5 in the hour now
        f.later(60)
        val tooMany = catch { f.create() } as TooManyRequestsException
        assertThat(tooMany.retryAfterSeconds).isEqualTo(3600 - 330) // now is 330s in; the first one leaves the hour at 3600s

        assertThat(f.metrics).contains("challenge:LOGIN:limited")
    }

    @Test
    fun `one email shared by several subjects gets at most ten codes an hour`() {
        val subjects = listOf("52998224725", "11144477735", "39053344705", "12345678909", "98765432100",
            "71428793860", "46281937084", "15350946056", "28465093756", "61763587022", "84136502743")
        subjects.take(10).forEach { f.create(subject = it, email = "familia@example.com") }

        assertThat(code { f.create(subject = subjects[10], email = "familia@example.com") }).isEqualTo("TOO_MANY_REQUESTS")
        f.create(subject = subjects[10], email = "outra@example.com") // the limit is per destination, not global
    }

    @Test
    fun `a code that could not be sent fails, does not count and is never verifiable`() {
        f.sender.failing = true
        assertThatThrownBy { f.create() }.isInstanceOf(CodeNotSentException::class.java)
        val failed = f.challenges.rows.values.single()
        assertThat(failed.status).isEqualTo(Challenge.Status.FAILED)

        f.sender.failing = false
        f.create() // no "wait 60s": the failed one did not count
        assertThat(code { f.verify(failed.id, "100001") }).isEqualTo("CHALLENGE_NOT_FOUND")
    }

    @Test
    fun `a code of one purpose does not verify another`() {
        val signup = f.create(purpose = Purpose.SIGNUP)
        val login = f.create(purpose = Purpose.LOGIN) // other purpose: no interval to wait

        assertThat(f.stored(signup.id).status).isEqualTo(Challenge.Status.OPEN) // not superseded by the login one
        f.verify(login.id, f.sender.lastCode())
        assertThat(code { f.verify(signup.id, f.sender.lastCode()) }).isEqualTo("INVALID_CODE")
    }

    @Test
    fun `malformed subjects and destinations are refused before anything is sent`() {
        assertThat(code { f.create(subject = "123") }).isEqualTo("INVALID_SUBJECT")
        assertThat(code { f.create(email = "não é email") }).isEqualTo("INVALID_DESTINATION")
        assertThat(f.sender.sent).isEmpty()
        assertThatThrownBy { f.create(subject = "") }.isInstanceOf(ValidationException::class.java)
    }

    private fun catch(call: ThrowingCallable): Throwable = org.assertj.core.api.Assertions.catchThrowable(call)
}

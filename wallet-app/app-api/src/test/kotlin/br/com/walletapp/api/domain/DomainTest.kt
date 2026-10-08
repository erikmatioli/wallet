package br.com.walletapp.api.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainTest {

    private val now = Instant.parse("2026-10-07T13:00:00Z")

    @Test
    fun `cpf accepts formatting, checks the digits and never prints itself in full`() {
        val cpf = Cpf.parse("529.982.247-25")
        assertThat(cpf.digits).isEqualTo("52998224725")
        assertThat(cpf.toString()).isEqualTo("***4725")
        assertThatThrownBy { Cpf.parse("529.982.247-26") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { Cpf.parse("111.111.111-11") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { Cpf.parse("52998224725x") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `password needs 8 to 72 characters with letters and digits`() {
        assertThat(Password.of("senha1234").toString()).isEqualTo("********")
        listOf("curta1", "somenteletras", "1234567890", "a1".repeat(37)).forEach { weak ->
            assertThatThrownBy { Password.of(weak) }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `the fifth wrong password locks the login for 15 minutes`() {
        var login = CustomerLogin.pending(Cpf.parse("52998224725"), "Maria  Silva", "hash", now)
        assertThat(login.name).isEqualTo("Maria Silva")

        repeat(4) { login = login.failedLogin(now) }
        assertThat(login.locked(now)).isFalse()
        login = login.failedLogin(now)

        assertThat(login.locked(now)).isTrue()
        assertThat(login.locked(now.plus(CustomerLogin.LOCK))).isFalse()
        assertThat(login.failedAttempts).isZero() // the count starts again after the lock
        assertThat(login.succeededLogin().lockedUntil).isNull()
    }
}

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
    fun `email is lower-cased, checked and never printed in full`() {
        val email = Email.of("  Maria.Silva@Example.COM ")
        assertThat(email.value).isEqualTo("maria.silva@example.com")
        assertThat(email.toString()).isEqualTo("m***@example.com")
        listOf("maria", "maria@", "@example.com", "maria @example.com").forEach { bad ->
            assertThatThrownBy { Email.of(bad) }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `a pending login has a tidy name and an email, and logs in only once active`() {
        val login = CustomerLogin.pending(Cpf.parse("52998224725"), "Maria  Silva", Email.of("maria@example.com"), now)
        assertThat(login.name).isEqualTo("Maria Silva")
        assertThat(login.canLogIn).isFalse()
        assertThat(login.activated(AccountId(java.util.UUID.randomUUID())).canLogIn).isTrue()
        assertThat(login.copy(status = LoginStatus.ACTIVE, email = null).canLogIn).isFalse() // made before ADR-002
        assertThatThrownBy { CustomerLogin.pending(login.cpf, "M", login.email!!, now) }
            .isInstanceOf(ValidationException::class.java)
    }
}

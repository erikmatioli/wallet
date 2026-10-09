package br.com.walletotp.domain

import br.com.walletotp.application.RandomCodes
import br.com.walletotp.application.Secrets
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class DomainTest {

    @Test
    fun `subjects are CPF or CNPJ, formatting dropped`() {
        assertThat(Subject.of("529.982.247-25").value).isEqualTo("52998224725")
        assertThat(Subject.of("12.abc.345/01de-35").value).isEqualTo("12ABC34501DE35")
        assertThat(Subject.of("52998224725").toString()).isEqualTo("***4725")
        assertThatThrownBy { Subject.of("1234") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `emails are lower-cased and masked`() {
        val email = Email.of("  Maria.Silva@Example.COM ")
        assertThat(email.value).isEqualTo("maria.silva@example.com")
        assertThat(email.masked).isEqualTo("m***@example.com")
        assertThat(email.toString()).isEqualTo("m***@example.com")
        assertThatThrownBy { Email.of("maria@") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `codes always have 6 digits, leading zeros kept`() {
        val codes = RandomCodes(SecureRandom())
        repeat(1000) { assertThat(codes.next()).matches("\\d{6}") }
    }

    @Test
    fun `the same code gives another hash on another challenge, and the key matters`() {
        val a = Secrets("key-a-0123456789abcdef0123456789ab".toByteArray())
        val b = Secrets("key-b-0123456789abcdef0123456789ab".toByteArray())
        val one = ChallengeId.new()
        val two = ChallengeId.new()

        assertThat(a.code(one, "123456")).isNotEqualTo(a.code(two, "123456"))
        assertThat(a.code(one, "123456")).isNotEqualTo(b.code(one, "123456"))
        assertThat(a.same(a.code(one, "123456"), a.code(one, "123456"))).isTrue()
        assertThatThrownBy { Secrets("short".toByteArray()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

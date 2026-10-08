package br.com.walletapp.api.adapter.out.security

import br.com.walletapp.api.application.port.PasswordHasher
import br.com.walletapp.api.domain.Password
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

/** BCrypt, cost 10: slow on purpose, so a leaked table cannot be brute-forced cheaply. */
@Component
class BCryptPasswordHasher : PasswordHasher {

    private val encoder = BCryptPasswordEncoder(10)

    override fun hash(password: Password): String = encoder.encode(password.value)!!

    override fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}

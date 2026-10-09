package br.com.walletotp.application

import br.com.walletotp.application.port.CodeGenerator
import br.com.walletotp.domain.ChallengeId
import br.com.walletotp.domain.Email
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hashes with the service's key (HMAC-SHA256, ADR-001 decision 4). A plain SHA-256 of 6 digits is broken
 * by hashing the million possible codes; without the key, a copy of the database does not reveal them.
 * Each kind of value gets its own prefix, so a code hash can never be mistaken for a context hash.
 *
 * @param key OTP_CODE_KEY, at least 32 bytes
 */
class Secrets(key: ByteArray) {

    private val keySpec = SecretKeySpec(key.copyOf(), ALGORITHM)

    init {
        require(key.size >= 32) { "the OTP code key must have at least 32 bytes" }
    }

    /** Bound to the challenge: the same 6 digits on two challenges give two different hashes. */
    fun code(challengeId: ChallengeId, code: String) = hmac("code", "$challengeId:$code")

    fun context(context: String) = hmac("context", context)

    fun destination(email: Email) = hmac("destination", email.value)

    /** Constant time: how long the comparison takes says nothing about how much of the code was right. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun hmac(kind: String, value: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(keySpec) }
        return HexFormat.of().formatHex(mac.doFinal("$kind\u001f$value".toByteArray()))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

/** 6 digits from a SecureRandom, leading zeros kept ("004217"). */
class RandomCodes(private val random: SecureRandom = SecureRandom()) : CodeGenerator {
    override fun next(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}

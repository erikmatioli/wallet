package br.com.walletotp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("otp")
data class OtpProperties(
    val walletCore: WalletCore,
    /**
     * The key of the HMACs that hide codes, contexts and destinations (ADR-001, decision 4). No default on
     * purpose: the service does not start without OTP_CODE_KEY (docker-compose.yml sets one locally).
     */
    val codeKey: String,
    val mail: Mail = Mail(),
) {
    /** The API accepts wallet-core's own JWTs, checked against its JWKS - no second identity provider. */
    data class WalletCore(val jwkSetUri: URI, val issuer: String)

    data class Mail(val from: String = "Wallet <nao-responda@wallet.local>")
}

package br.com.walletcore.adapter.in.rest.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings. In production provide both key paths (PKCS#8 private key + X.509 public key,
 * PEM) mounted from a secret manager; without them an ephemeral key pair is generated at startup
 * (development only: tokens do not survive a restart and differ per instance).
 */
@ConfigurationProperties(prefix = "wallet.security.jwt")
public record JwtProperties(String issuer, Duration ttl, String keyId, String privateKeyPath, String publicKeyPath) {

    public JwtProperties {
        issuer = issuer == null || issuer.isBlank() ? "wallet-core" : issuer;
        ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
        keyId = keyId == null || keyId.isBlank() ? "wallet-core-1" : keyId;
    }
}

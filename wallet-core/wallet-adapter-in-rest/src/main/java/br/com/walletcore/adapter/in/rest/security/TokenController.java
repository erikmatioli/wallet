package br.com.walletcore.adapter.in.rest.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TokenController {

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final RSAKey signingKey;
    private final Clock clock;

    TokenController(JwtEncoder encoder, JwtProperties props, RSAKey signingKey, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    record TokenResponse(String access_token, String token_type, long expires_in, String scope) {
    }

    /** Client credentials: authenticated by HTTP Basic (see SecurityConfig). */
    @PostMapping("/v1/auth/token")
    ResponseEntity<TokenResponse> token(Authentication authentication) {
        TenantPrincipal principal = (TenantPrincipal) authentication.getPrincipal();
        Instant now = clock.instant();
        String scope = String.join(" ", principal.scopes().stream().sorted().toList());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(principal.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(props.ttl()))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", principal.tenantId().value().toString())
                .claim("scope", scope)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(props.keyId()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(token, "Bearer", props.ttl().toSeconds(), scope));
    }

    /** Public keys so that consumers can verify tokens offline. */
    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}

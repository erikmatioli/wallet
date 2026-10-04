package br.com.walletcore.adapter.in.rest.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Authentication & Security", description = "Endpoints para emissão de tokens JWT (Client Credentials) e exposição de chaves públicas JWKS.")
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

    @Schema(description = "Resposta contendo o token de acesso OAuth2/JWT emitido.")
    record TokenResponse(
            @Schema(description = "Token JWT assinado no formato Bearer", example = "eyJhbGciOiJSUzI1NiIs...")
            String access_token,

            @Schema(description = "Tipo do token", example = "Bearer")
            String token_type,

            @Schema(description = "Tempo de expiração em segundos", example = "3600")
            long expires_in,

            @Schema(description = "Escopos de permissão concedidos", example = "wallet:read wallet:write")
            String scope) {
    }

    /** Client credentials: authenticated by HTTP Basic (see SecurityConfig). */
    @PostMapping("/v1/auth/token")
    @Operation(
            summary = "Obter Token de Acesso (Client Credentials)",
            description = "Autentica o cliente via credenciais HTTP Basic (Client ID e Client Secret) e retorna um token JWT para consumo seguro das APIs protegidas."
    )
    @SecurityRequirement(name = "basicAuth") // Indica que este endpoint usa Basic Auth em vez de Bearer
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Token emitido com sucesso"),
            @ApiResponse(responseCode = "400", description = "invalid_scope: escopo pedido não pertence ao cliente"),
            @ApiResponse(responseCode = "401", description = "Credenciais de cliente inválidas ou ausentes no cabeçalho Authorization Basic")
    })
    ResponseEntity<?> token(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(description = "Escopos desejados, separados por espaço (OAuth2 client credentials). "
                    + "Ausente = todos os escopos do tenant. Ex.: \"pix:send\" para um token que só debita e estorna.",
                    example = "pix:send")
            @RequestParam(name = "scope", required = false) String requestedScope) {
        TenantPrincipal principal = (TenantPrincipal) authentication.getPrincipal();
        Instant now = clock.instant();
        Set<String> granted = principal.scopes();
        if (requestedScope != null && !requestedScope.isBlank()) {
            // Down-scoping only: a client may ask for fewer scopes than it has (least privilege
            // per use - e.g. the Pix service's send context), never for one it was not given.
            Set<String> requested = Arrays.stream(requestedScope.trim().split("\\s+")).collect(Collectors.toSet());
            if (!granted.containsAll(requested)) {
                return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "invalid_scope",
                                "error_description", "requested scope exceeds the scopes granted to this client"));
            }
            granted = requested;
        }
        String scope = String.join(" ", granted.stream().sorted().toList());

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
    @Operation(
            summary = "Obter Chaves Públicas (JWKS)",
            description = "Retorna o JSON Web Key Set contendo as chaves públicas utilizadas para assinar os tokens JWT, permitindo que serviços externos validem os tokens de forma offline."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Conjunto de chaves públicas retornado com sucesso")
    })
    Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
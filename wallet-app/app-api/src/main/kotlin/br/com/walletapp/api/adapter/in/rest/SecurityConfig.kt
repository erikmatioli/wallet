package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.config.SessionClaims
import br.com.walletapp.contract.AppError
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.spec.SecretKeySpec

/**
 * Only app-api's own customer tokens open the API (ADR-001, decision 8): HS256 with a key only this
 * service has. Signup, login and info are open; everything else needs a valid, unexpired token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private fun key(props: AppProperties) = SecretKeySpec(props.session.secret.toByteArray(), "HmacSHA256")

    @Bean
    fun jwtEncoder(props: AppProperties): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key(props)))

    /** exp and nbf, plus this instance's issuer and tenant: a token of another tenant's app is refused here. */
    @Bean
    fun jwtDecoder(props: AppProperties): JwtDecoder {
        val tenant = props.walletCore.clientId
        return NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(SessionClaims.issuer(tenant)),
                JwtClaimValidator<String>(SessionClaims.TENANT) { it == tenant },
            ))
        }
    }

    @Bean
    fun api(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests {
            // /error: Spring forwards failed requests there; denying it would turn every 400 into a 401.
            it.requestMatchers("/actuator/**", "/error", "/app/v1/info").permitAll()
                .requestMatchers(HttpMethod.POST, "/app/v1/signup/start", "/app/v1/signup/confirm", "/app/v1/login/start",
                    "/app/v1/login/confirm").permitAll()
                .requestMatchers("/app/v1/**").authenticated()
                .anyRequest().denyAll()
        }
            .oauth2ResourceServer {
                it.jwt(Customizer.withDefaults())
                it.authenticationEntryPoint(sessionExpired)
            }
            .exceptionHandling { it.authenticationEntryPoint(sessionExpired) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        return http.build()
    }

    /** No token, or an invalid or expired one: the contract's error, so the desktop knows to ask for login. */
    private val sessionExpired = AuthenticationEntryPoint { _, response, _ ->
        response.status = 401
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(Json.encodeToString(AppError("SESSION_EXPIRED", "Sua sessão expirou. Entre de novo.")))
    }
}

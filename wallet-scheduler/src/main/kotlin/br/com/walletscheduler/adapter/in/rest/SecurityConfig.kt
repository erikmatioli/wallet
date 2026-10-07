package br.com.walletscheduler.adapter.`in`.rest

import br.com.walletscheduler.config.SchedulerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * The API accepts the JWTs wallet-core issues - no second identity provider, same as wallet-pix.
 * Reads need schedules:read, changes schedules:write (granted to every tenant by wallet-core's V5).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun jwtDecoder(props: SchedulerProperties): JwtDecoder =
        // The JWKS is fetched on first use, so this service can start before wallet-core.
        NimbusJwtDecoder.withJwkSetUri(props.walletCore.jwkSetUri.toString()).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(props.walletCore.issuer))
        }

    @Bean
    fun api(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests {
            // /error: Spring forwards failed requests there; denying it would turn every 400 into a 403.
            it.requestMatchers("/actuator/**", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/schedules", "/v1/schedules/*").hasAuthority("SCOPE_schedules:read")
                .requestMatchers(HttpMethod.POST, "/v1/schedules", "/v1/schedules/*/cancel")
                .hasAuthority("SCOPE_schedules:write")
                .anyRequest().denyAll()
        }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        return http.build()
    }
}

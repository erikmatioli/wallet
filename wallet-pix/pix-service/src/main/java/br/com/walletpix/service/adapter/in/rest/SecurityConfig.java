package br.com.walletpix.service.adapter.in.rest;

import br.com.walletpix.service.config.PixProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The send API accepts the JWTs wallet-core issues - no second identity provider. Tokens are
 * verified against wallet-core's JWKS (signature, expiry, issuer) and must carry
 * {@code pix:send}; a tenant asks wallet-core for one with {@code POST /v1/auth/token?scope=pix:send}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(PixProperties props) {
        // The JWKS is fetched on first use, so this service can start before wallet-core.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.walletCore().jwkSetUri().toString()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.walletCore().issuer()));
        return decoder;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        // /error: Spring forwards failed requests (e.g. bean validation) there; denying it
                        // would turn every 400 into a misleading 403.
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/pix/payments").hasAuthority("SCOPE_pix:send")
                        .requestMatchers(HttpMethod.GET, "/v1/pix/payments/*").hasAuthority("SCOPE_pix:send")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

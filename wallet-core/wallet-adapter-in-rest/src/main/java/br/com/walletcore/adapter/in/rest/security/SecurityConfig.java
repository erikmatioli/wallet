package br.com.walletcore.adapter.in.rest.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two stateless filter chains:
 * <ol>
 *   <li>{@code POST /v1/auth/token}: HTTP Basic (client id + secret) -> short-lived JWT;</li>
 *   <li>everything else: Bearer JWT, with per-endpoint scope checks. Unknown routes are denied.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt by default
    }

    @Bean
    @Order(1)
    SecurityFilterChain tokenEndpointChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/auth/token")
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/.well-known/jwks.json", "/actuator/health/**", "/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/customers").hasAuthority("SCOPE_customers:write")
                        .requestMatchers(HttpMethod.GET, "/v1/accounts/*/audit").hasAuthority("SCOPE_ledger:audit")
                        .requestMatchers(HttpMethod.GET, "/v1/accounts/**").hasAuthority("SCOPE_accounts:read")
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/*/deposits", "/v1/accounts/*/withdrawals",
                                "/v1/transfers").hasAuthority("SCOPE_ledger:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

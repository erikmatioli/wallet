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
                        .requestMatchers("/.well-known/jwks.json", "/actuator/health/**", "/actuator/**", "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/customers").hasAuthority("SCOPE_customers:write")
                        // The customer's email (ADR-003 of wallet-app): the operator changes it like onboarding;
                        // reading it is a read of the customer, as the account reads are.
                        .requestMatchers(HttpMethod.PUT, "/v1/customers/*/email").hasAuthority("SCOPE_customers:write")
                        .requestMatchers(HttpMethod.GET, "/v1/customers/contact").hasAuthority("SCOPE_accounts:read")
                        .requestMatchers(HttpMethod.GET, "/v1/accounts/*/audit").hasAuthority("SCOPE_ledger:audit")
                        // Bare "/v1/accounts" (the directory listing) explicitly, rather than relying on
                        // "/v1/accounts/**" to also match the zero-segment case: Spring Security 6 uses
                        // PathPatternParser by default, whose "**" semantics for a bare prefix differ from
                        // the legacy AntPathMatcher's - safer to say what we mean than to rely on it.
                        .requestMatchers(HttpMethod.GET, "/v1/accounts", "/v1/accounts/lookup")
                        .hasAuthority("SCOPE_accounts:read")
                        .requestMatchers(HttpMethod.GET, "/v1/accounts/**").hasAuthority("SCOPE_accounts:read")
                        // A read despite being a POST: it only carries the CPF/CNPJ in the body (see
                        // AccountDirectoryController#holderCheck), it changes nothing.
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/holder-check").hasAuthority("SCOPE_accounts:read")
                        // pix:send (outgoing-Pix context) may debit and reverse its debits, nothing more:
                        // deposits and transfers stay ledger:write only, so a leaked send token cannot
                        // credit anyone or move money between customers.
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/*/withdrawals", "/v1/transactions/*/reversals")
                        .hasAnyAuthority("SCOPE_ledger:write", "SCOPE_pix:send")
                        // Pix movements (ADR-010) only with the Pix scopes, never ledger:write: each one
                        // carries its detail and goes through the core's Pix rules.
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/*/pix-debits").hasAuthority("SCOPE_pix:send")
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/*/pix-credits").hasAuthority("SCOPE_pix:receive")
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/*/deposits", "/v1/transfers")
                        .hasAuthority("SCOPE_ledger:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

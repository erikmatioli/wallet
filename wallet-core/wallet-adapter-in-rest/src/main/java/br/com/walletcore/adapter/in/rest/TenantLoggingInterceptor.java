package br.com.walletcore.adapter.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Puts {@code tenant_id} in the logging MDC for the lifetime of the request, so every log line
 * - including ones written deep in the persistence adapter, with no reference to the HTTP layer -
 * can be filtered by tenant. Runs as an MVC interceptor (after Spring Security has already
 * authenticated the request and resolved the JWT), not a servlet filter, precisely so it does not
 * need to worry about filter-chain ordering relative to Spring Security.
 *
 * <p>{@code trace_id}/{@code span_id} are added to the MDC automatically by the OpenTelemetry
 * Java agent (see {@code docker-compose.yml}); this class only
 * adds the one field tracing does not know about: which tenant the request belongs to.
 */
@Component
class TenantLoggingInterceptor implements HandlerInterceptor {

    static final String MDC_KEY = "tenant_id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            MDC.put(MDC_KEY, jwt.getClaimAsString("tenant_id"));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        MDC.remove(MDC_KEY); // request threads are pooled/virtual and reused: never leak into the next request
    }
}

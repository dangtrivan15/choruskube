package com.choruskube.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts the Fleet bearer token for {@code /worker/**} and rejects requests without one.
 *
 * <p>Deliberately a sibling of {@link InternalAuthFilter} rather than an extension of it: that
 * file is change-gated, and its orchestrator/agent tiers carry semantics that do not apply to
 * Worker registration. This filter only proves a bearer token was presented; deciding whether
 * that token names a Fleet is {@code WorkerRegistrar}'s job, so a bad token is a 403 from the
 * domain layer rather than a 401 here — which is also what lets a deployment vary how many Fleets
 * exist without touching the filter chain.
 *
 * <p>A plain {@code @Component} filter is auto-registered by Spring Boot into the servlet
 * container's filter chain independently of {@code auth.enabled} — that is what keeps Worker
 * registration gated by a Fleet token when {@link NoAuthConfigurer} is the active strategy. An
 * auth-enabled configurer may <em>also</em> wire it explicitly via {@code addFilterBefore}, so it
 * appears twice in the chain; that is harmless because {@link OncePerRequestFilter} guards
 * {@code doFilterInternal} with a per-request "already filtered" marker, same as
 * {@link InternalAuthFilter}, which relies on the identical double registration.
 *
 * <p><b>Do not "clean up" the double registration by wrapping this bean in a {@code
 * FilterRegistrationBean} with {@code setEnabled(false)}.</b> That would suppress the generic
 * auto-registration, which is this filter's <em>only</em> path into the chain when {@code
 * auth.enabled=false} — turning {@code /worker/register} into an anonymous write endpoint on
 * every self-hosted install.
 */
@Component
public class WorkerAuthFilter extends OncePerRequestFilter {

    public static final String FLEET_TOKEN_ATTRIBUTE = "choruskube.fleetToken";

    private static final String PREFIX = "Bearer ";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/worker/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX) || header.length() == PREFIX.length()) {
            // Same {"error":"..."} shape as InternalAuthFilter, not response.sendError's Spring Boot
            // /error body — two machine-facing 401 routes should look identical to a caller.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing fleet token\"}");
            return;
        }

        request.setAttribute(FLEET_TOKEN_ATTRIBUTE, header.substring(PREFIX.length()));
        chain.doFilter(request, response);
    }
}

package com.choruskube.core.config;

import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.repository.NodeExecutionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication filter for all {@code /internal/**} and {@code /api/**} endpoints.
 *
 * <p>Two-tier auth model:
 * <ul>
 *   <li><b>Orchestrator:</b> Bearer token whose SHA-256 hash matches the configured
 *       {@code internal.auth.orchestrator-secret-hash}. Full access to all internal endpoints.</li>
 *   <li><b>Agent:</b> Bearer token whose SHA-256 hash matches the {@code job_secret_hash} stored on
 *       the node execution named at the fixed position {@code /internal/runs/{runId}/node-executions/{nodeExecId}}
 *       of the <em>decoded</em> path. The execution's run must also equal the path's run — an agent's
 *       own secret is refused outside its own run.</li>
 * </ul>
 *
 * <p>Every decision — which prefix a request falls under, and where the run/execution ids sit —
 * is made on the decoded path segments the router itself dispatches on ({@link #decodedSegments}),
 * so a percent-escaped spelling of either prefix is authenticated or refused exactly like the plain
 * one, never skipped.
 *
 * <p>Mode is controlled by {@code internal.auth.mode}:
 * <ul>
 *   <li>{@code enforce} (default) — reject unauthenticated/unauthorized requests with 401.</li>
 *   <li>{@code warn} — log violations but allow the request through.</li>
 * </ul>
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    private final String orchestratorSecretHash;
    private final String authMode;
    private final NodeExecutionRepository execRepo;

    public InternalAuthFilter(
            @Value("${internal.auth.orchestrator-secret-hash:}") String orchestratorSecretHash,
            @Value("${internal.auth.mode:enforce}") String authMode,
            NodeExecutionRepository execRepo) {
        this.orchestratorSecretHash = orchestratorSecretHash;
        this.authMode = authMode;
        this.execRepo = execRepo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        List<String> segments = decodedSegments(request);
        return segments.isEmpty()
                || !(segments.get(0).equals("internal") || segments.get(0).equals("api"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // If no orchestrator secret hash is configured, auth is not yet set up — pass through
        if (orchestratorSecretHash == null || orchestratorSecretHash.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        List<String> segments = decodedSegments(request);
        boolean isApi = !segments.isEmpty() && segments.get(0).equals("api");

        if (isApi) {
            // Public API: reject requests carrying agent or orchestrator tokens
            String rejection = rejectAgentOnPublicApi(request);
            if (rejection != null) {
                log.error("AGENT_PUBLIC_API_REJECT: {} {} — {}", request.getMethod(), uri, rejection);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"" + rejection.replace("\"", "'") + "\"}");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // /internal/ path — existing authentication logic
        String authFailure = authenticate(request, segments);
        if (authFailure == null) {
            // Authentication succeeded
            filterChain.doFilter(request, response);
            return;
        }

        // Authentication failed
        if ("warn".equalsIgnoreCase(authMode)) {
            log.warn("INTERNAL_AUTH_WARN: {} {} — {}", request.getMethod(), uri, authFailure);
            filterChain.doFilter(request, response);
        } else {
            log.error("INTERNAL_AUTH_REJECT: {} {} — {}", request.getMethod(), uri, authFailure);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + authFailure.replace("\"", "'") + "\"}");
        }
    }

    /**
     * Checks if a public API request carries a known agent or orchestrator token.
     *
     * @return rejection reason if the request should be blocked, or null to allow it.
     */
    private String rejectAgentOnPublicApi(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null; // No Bearer token — normal public request
        }

        String token = authHeader.substring("Bearer ".length());
        String tokenHash = sha256Hex(token);

        if (tokenHash.equals(orchestratorSecretHash)) {
            return "Orchestrator must use /internal/ endpoints";
        }

        if (execRepo.existsByJobSecretHash(tokenHash)) {
            return "Agent pods must use /internal/ endpoints";
        }

        return null; // Unknown Bearer token — allow (future user auth)
    }

    /**
     * Attempts to authenticate the request.
     *
     * @param segments the decoded path-within-application segments, as returned by {@link #decodedSegments}.
     * @return null if authenticated, or a reason string if authentication failed.
     */
    private String authenticate(HttpServletRequest request, List<String> segments) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return "Missing or invalid Authorization header";
        }

        String token = authHeader.substring("Bearer ".length());
        String tokenHash = sha256Hex(token);

        // Tier 1: Check if this is the orchestrator
        if (tokenHash.equals(orchestratorSecretHash)) {
            return null; // Orchestrator has full access
        }

        // Tier 2: an agent's JOB_SECRET, scoped to its own execution under its own run. The run and
        // execution ids are read only at this fixed window — anywhere else they authenticate nothing.
        if (segments.size() >= 5
                && segments.get(0).equals("internal")
                && segments.get(1).equals("runs")
                && segments.get(3).equals("node-executions")) {
            try {
                UUID runId = UUID.fromString(segments.get(2));
                UUID nodeExecId = UUID.fromString(segments.get(4));
                Optional<NodeExecution> execOpt = execRepo.findById(nodeExecId);
                if (execOpt.isPresent()) {
                    NodeExecution exec = execOpt.get();
                    String storedHash = exec.getJobSecretHash();
                    if (storedHash != null
                            && tokenHash.equals(storedHash)
                            && exec.getWorkflowRunId().equals(runId)) {
                        return null; // Agent authenticated for its own execution, in its own run
                    }
                }
            } catch (IllegalArgumentException e) {
                // Non-UUID run or execution segment — fall through to unauthorized.
            }
        }

        return "Invalid or insufficient Bearer token";
    }

    /**
     * The decoded, path-parameter-free segments of {@code request}'s path within the application —
     * the same segments {@code PathPattern} routing matches on. Reading these instead of the raw
     * URI is what lets a percent-escaped spelling of a prefix or an id be authenticated (or refused)
     * exactly like its plain form, rather than slipping past {@link #shouldNotFilter} unauthenticated.
     */
    private static List<String> decodedSegments(HttpServletRequest request) {
        PathContainer pathWithinApplication = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication();
        List<String> segments = new ArrayList<>();
        for (PathContainer.Element element : pathWithinApplication.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                segments.add(segment.valueToMatch());
            }
        }
        return segments;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

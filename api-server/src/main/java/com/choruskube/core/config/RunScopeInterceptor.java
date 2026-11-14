package com.choruskube.core.config;

import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * A second, independent layer under {@link InternalAuthFilter} and {@link WorkerAuthFilter}. Those
 * filters authenticate on the decoded path the router dispatches on and, for the internal tier,
 * compare the authenticated execution's run against the path's run — but they are change-gated
 * files, so this guard keeps enforcing both rules itself rather than trusting their next edit:
 * it refuses a percent-escaped path on every route either filter is meant to authenticate, and it
 * refuses an execution that does not belong to the path's run on every execution-scoped route,
 * including the orchestrator tier, which the filters never check against the run at all.
 */
@Component
public class RunScopeInterceptor implements HandlerInterceptor {

    public static final String ESCAPED_PATH_MESSAGE = "Percent-encoded paths are not accepted here";

    private final NodeExecutionRepository execRepo;

    public RunScopeInterceptor(NodeExecutionRepository execRepo) {
        this.execRepo = execRepo;
    }

    /** Whether {@code pattern} is {@code /{internal|worker}/runs/{var}/node-executions/{var}[/...]}. */
    public static boolean isExecutionScoped(String pattern) {
        String[] segments = pattern.split("/");
        return segments.length >= 6
                && (segments[1].equals("internal") || segments[1].equals("worker"))
                && segments[2].equals("runs")
                && segments[3].startsWith("{")
                && segments[4].equals("node-executions")
                && segments[5].startsWith("{");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        Object patternAttr = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (patternAttr == null) {
            // Spring answers a plain OPTIONS with its own Allow-header handler and records no
            // pattern for it; any other unmatched HandlerMethod is unexplained, so refuse it.
            if (HttpMethod.OPTIONS.matches(request.getMethod())) {
                return true;
            }
            throw new IllegalStateException("No matched pattern for a dispatched HandlerMethod request");
        }
        String pattern = patternAttr.toString();
        boolean executionScoped = isExecutionScoped(pattern);

        if ((pattern.startsWith("/internal/") || executionScoped)
                && request.getRequestURI().indexOf('%') >= 0) {
            throw new BadRequestException(ESCAPED_PATH_MESSAGE);
        }

        if (!executionScoped) {
            return true;
        }

        String[] segments = pattern.split("/");
        if (!segments[3].equals("{runId}") || !segments[5].equals("{nodeExecId}")) {
            throw new IllegalStateException("Route " + pattern + " must name its variables {runId} and {nodeExecId}");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> vars =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        UUID runId;
        UUID nodeExecId;
        try {
            runId = UUID.fromString(vars.get("runId"));
            nodeExecId = UUID.fromString(vars.get("nodeExecId"));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid run or node execution id");
        }

        if (!execRepo.existsByIdAndWorkflowRunId(nodeExecId, runId)) {
            throw NodeExecutionUtil.notFound(nodeExecId);
        }

        return true;
    }
}

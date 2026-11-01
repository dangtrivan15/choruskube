package com.choruskube.core.config;

import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Runs after {@link InternalAuthFilter} and {@link WorkerAuthFilter} but before any handler binds
 * or validates its request, on every {@code /internal/**} and {@code /worker/runs/**} route.
 *
 * <p>Both filters gate on the request's raw, still-escaped path, while Spring MVC dispatches on
 * the decoded one — so a percent-escaped path they would have rejected can still reach a handler
 * with no credential at all. This interceptor refuses that path directly rather than trusting a
 * filter's decision it cannot observe. Where a route names a run and a node execution, it also
 * refuses a pair that does not belong together, with the same answer a caller already gets for an
 * execution that does not exist — so an execution in another run is indistinguishable from a
 * missing one. The route's path-variable names are a contract: an execution-scoped route must call
 * them {@code {runId}} and {@code {nodeExecId}}, or this interceptor fails closed with 500.
 */
@Component
public class RunScopeInterceptor implements HandlerInterceptor {

    public static final String ESCAPED_PATH_MESSAGE = "Percent-encoded paths are not accepted here";

    private final NodeExecutionRepository execRepo;

    public RunScopeInterceptor(NodeExecutionRepository execRepo) {
        this.execRepo = execRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        Object patternAttr = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (patternAttr == null) {
            // The handler-mapping machinery that resolved this HandlerMethod always sets this
            // attribute; its absence means this interceptor cannot tell what route it is guarding.
            throw new IllegalStateException("No matched pattern for a dispatched HandlerMethod request");
        }
        String pattern = patternAttr.toString();

        String[] segments = pattern.split("/");
        // segments[0] is the empty string before the leading '/'.
        boolean executionScoped = segments.length >= 6
                && (segments[1].equals("internal") || segments[1].equals("worker"))
                && segments[2].equals("runs")
                && segments[3].startsWith("{")
                && segments[4].equals("node-executions")
                && segments[5].startsWith("{");

        boolean escapeRuleApplies = pattern.startsWith("/internal/") || executionScoped;
        if (escapeRuleApplies && request.getRequestURI().indexOf('%') >= 0) {
            throw new BadRequestException(ESCAPED_PATH_MESSAGE);
        }

        if (!executionScoped) {
            return true;
        }

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

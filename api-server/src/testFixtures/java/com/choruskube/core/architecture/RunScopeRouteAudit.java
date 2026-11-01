package com.choruskube.core.architecture;

import com.choruskube.core.config.RunScopeInterceptor;
import com.choruskube.core.util.NodeExecutionUtil;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Proves {@link RunScopeInterceptor} covers every execution-scoped route it must, and that the
 * {@code InternalAuthFilter}/{@code WorkerAuthFilter} escape bypass it compensates for is closed
 * everywhere those filters are meant to apply. Mirrors {@code ControlPlaneGitHubCallGuard}'s shape:
 * a fluent builder, one {@link AssertionError} naming every violation, and its own vacuity check.
 *
 * <p>Each repo that composes {@code /internal/**} and {@code /worker/runs/**} routes runs this
 * fixture over its own {@link RequestMappingHandlerMapping} and {@link MockMvc}, so a route the
 * composing application adds is audited without anyone remembering to list it.
 */
public final class RunScopeRouteAudit {

    private final RequestMappingHandlerMapping mapping;
    private final MockMvc mvc;
    private final List<Route> fakeRoutes;
    private final Probe fakeProbe;

    private UUID executionRunId;
    private UUID executionNodeExecId;
    private String executionJobSecret;
    private UUID foreignRunId;

    private List<Route> routes;

    private RunScopeRouteAudit(
            RequestMappingHandlerMapping mapping, MockMvc mvc, List<Route> fakeRoutes, Probe fakeProbe) {
        this.mapping = mapping;
        this.mvc = mvc;
        this.fakeRoutes = fakeRoutes;
        this.fakeProbe = fakeProbe;
    }

    /** Scans {@code mapping}'s route table; probes real requests through {@code mvc}. */
    public static RunScopeRouteAudit of(RequestMappingHandlerMapping mapping, MockMvc mvc) {
        return new RunScopeRouteAudit(mapping, mvc, null, null);
    }

    /** Test seam: audits a fixed {@code routes} list via {@code probe} instead of a real Spring context. */
    public static RunScopeRouteAudit over(List<Route> routes, Probe probe) {
        return new RunScopeRouteAudit(null, null, List.copyOf(routes), probe);
    }

    /** The execution/run/secret R1 and R3 probe as the caller's own, matching pair. */
    public RunScopeRouteAudit executionInRun(UUID runId, UUID nodeExecId, String jobSecret) {
        this.executionRunId = runId;
        this.executionNodeExecId = nodeExecId;
        this.executionJobSecret = jobSecret;
        return this;
    }

    /** A run the execution from {@link #executionInRun} does NOT belong to. */
    public RunScopeRouteAudit foreignRun(UUID runId) {
        this.foreignRunId = runId;
        return this;
    }

    /** Every audited route, as {@code "METHOD pattern"} — proof the walk actually found routes. */
    public SortedSet<String> routes() {
        SortedSet<String> result = new TreeSet<>();
        for (Route route : routeList()) {
            result.add(route.method() + " " + route.pattern());
        }
        return result;
    }

    /** Fails with one {@link AssertionError} naming every R1-R4 violation. */
    public void check() {
        List<String> problems = new ArrayList<>();
        List<Route> rs = routeList();

        if (executionRunId == null || executionNodeExecId == null || executionJobSecret == null) {
            problems.add("R4: executionInRun(...) was never called; the walk would pass vacuously.");
        }
        if (foreignRunId == null) {
            problems.add("R4: foreignRun(...) was never called; the walk would pass vacuously.");
        }

        boolean anyExecInternal = false;
        boolean anyExecWorker = false;
        boolean anyRunLevelInternal = false;
        for (Route route : rs) {
            boolean execScoped = isExecutionScoped(route.pattern());
            boolean isInternal = route.pattern().startsWith("/internal/");
            boolean isWorker = route.pattern().startsWith("/worker/runs/");
            anyExecInternal |= isInternal && execScoped;
            anyExecWorker |= isWorker && execScoped;
            anyRunLevelInternal |= isInternal && !execScoped;
        }
        if (!anyExecInternal) {
            problems.add("R4: no execution-scoped /internal route found; the walk would pass vacuously.");
        }
        if (!anyExecWorker) {
            problems.add("R4: no execution-scoped /worker route found; the walk would pass vacuously.");
        }
        if (!anyRunLevelInternal) {
            problems.add("R4: no run-level /internal route found; the walk would pass vacuously.");
        }

        for (Route route : rs) {
            boolean execScoped = isExecutionScoped(route.pattern());
            boolean isInternal = route.pattern().startsWith("/internal/");
            boolean isWorker = route.pattern().startsWith("/worker/runs/");

            if (execScoped) {
                checkR1(route, problems);
            }
            if (isInternal || (isWorker && execScoped)) {
                checkR2(route, problems);
            }
            if (isInternal && !execScoped) {
                checkR3(route, problems);
            }
        }

        if (!problems.isEmpty()) {
            throw new AssertionError(String.join("\n", problems));
        }
    }

    private void checkR1(Route route, List<String> problems) {
        String label = route.method() + " " + route.pattern();
        if (!route.guarded()) {
            problems.add("R1: " + label + " is execution-scoped but RunScopeInterceptor is not in its handler chain.");
        }
        if (executionNodeExecId == null || foreignRunId == null) {
            return;
        }
        String bearer = route.pattern().startsWith("/internal/") ? executionJobSecret : "audit-probe";
        URI uri = URI.create(expand(route.pattern(), foreignRunId, executionNodeExecId));
        Response response = probe().send(route.method(), uri, bearer);
        String expectedBody = NodeExecutionUtil.notFound(executionNodeExecId).getMessage();
        if (response.status() != 404 || !expectedBody.equals(response.body())) {
            problems.add("R1: " + label + " did not refuse a foreign pair: expected 404 \"" + expectedBody + "\", got "
                    + response.status() + " \"" + response.body() + "\".");
        }
    }

    private void checkR2(Route route, List<String> problems) {
        if (executionRunId == null || executionNodeExecId == null) {
            return;
        }
        String label = route.method() + " " + route.pattern();
        String expanded = expand(route.pattern(), executionRunId, executionNodeExecId);
        URI uri = URI.create(escapeLeadingSegment(expanded));
        Response response = probe().send(route.method(), uri, null);
        if (response.status() != 400 || !RunScopeInterceptor.ESCAPED_PATH_MESSAGE.equals(response.body())) {
            problems.add("R2: " + label + " did not refuse its escaped spelling: expected 400 \""
                    + RunScopeInterceptor.ESCAPED_PATH_MESSAGE + "\", got " + response.status() + " \""
                    + response.body() + "\".");
        }
    }

    private void checkR3(Route route, List<String> problems) {
        String label = route.method() + " " + route.pattern();
        checkStructural(route.pattern(), label, problems);

        if (executionRunId == null || executionJobSecret == null) {
            return;
        }
        String expanded = expand(route.pattern(), executionRunId, null);
        Response response = probe().send(route.method(), URI.create(expanded), executionJobSecret);
        if (response.status() != 401) {
            problems.add("R3: " + label
                    + " did not refuse an agent secret with 401 (is internal auth enforced in this context?); got "
                    + response.status() + ".");
        }
    }

    private static void checkStructural(String pattern, String label, List<String> problems) {
        String[] segments = segmentsOf(pattern);
        for (String segment : segments) {
            if (segment.contains("**")) {
                problems.add("R3: " + label + " pattern contains a catch-all segment (**).");
            }
            if (isVariable(segment)) {
                String inner = segment.substring(1, segment.length() - 1);
                if (inner.startsWith("*") || inner.contains(":")) {
                    problems.add(
                            "R3: " + label + " pattern contains a multi-segment or regex-constrained path variable.");
                }
            }
        }
        for (int i = 0; i + 4 < segments.length; i++) {
            if (matchesAgentWindow(segments, i)) {
                problems.add("R3: " + label + " pattern has a window at segment " + i
                        + " shaped like /internal/runs/{runId}/node-executions/{nodeExecId}, which"
                        + " InternalAuthFilter's unanchored match could spell from an agent-controlled value.");
            }
        }
    }

    private static boolean matchesAgentWindow(String[] segments, int i) {
        return matchesLiteralOrVariable(segments[i], "internal")
                && matchesLiteralOrVariable(segments[i + 1], "runs")
                && matchesLiteralOrVariable(segments[i + 3], "node-executions")
                && isVariable(segments[i + 4]);
    }

    private static boolean matchesLiteralOrVariable(String segment, String literal) {
        return segment.equals(literal) || isVariable(segment);
    }

    private Probe probe() {
        return fakeProbe != null ? fakeProbe : this::sendReal;
    }

    private Response sendReal(String method, URI uri, String bearer) {
        try {
            var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), uri);
            if (bearer != null) {
                builder.header("Authorization", "Bearer " + bearer);
            }
            MvcResult result = mvc.perform(builder).andReturn();
            return new Response(
                    result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new RuntimeException("Probe request failed for " + method + " " + uri, e);
        }
    }

    private List<Route> routeList() {
        if (routes == null) {
            routes = fakeRoutes != null ? fakeRoutes : enumerateReal();
        }
        return routes;
    }

    private List<Route> enumerateReal() {
        List<Route> result = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            List<String> methodNames = methods.isEmpty()
                    ? List.of("GET")
                    : methods.stream().map(Enum::name).toList();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/internal/") && !pattern.startsWith("/worker/runs/")) {
                    continue;
                }
                for (String methodName : methodNames) {
                    result.add(new Route(methodName, pattern, isGuardedReal(pattern, methodName)));
                }
            }
        }
        return result;
    }

    private boolean isGuardedReal(String pattern, String methodName) {
        String path = expand(pattern, UUID.randomUUID(), UUID.randomUUID());
        MockHttpServletRequest request = new MockHttpServletRequest(methodName, path);
        ServletRequestPathUtils.parseAndCache(request);
        try {
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain == null) {
                return false;
            }
            for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
                if (interceptor instanceof RunScopeInterceptor) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code {runId}} and {@code {nodeExecId}} take the given ids; every other {@code {var}} gets a random UUID. */
    private static String expand(String pattern, UUID runId, UUID nodeExecId) {
        StringBuilder result = new StringBuilder();
        for (String segment : segmentsOf(pattern)) {
            result.append('/');
            if (segment.equals("{runId}")) {
                result.append(runId);
            } else if (segment.equals("{nodeExecId}")) {
                result.append(nodeExecId != null ? nodeExecId : UUID.randomUUID());
            } else if (isVariable(segment)) {
                result.append(UUID.randomUUID());
            } else {
                result.append(segment);
            }
        }
        return result.toString();
    }

    private static String escapeLeadingSegment(String path) {
        if (path.startsWith("/internal/")) {
            return "/%69nternal/" + path.substring("/internal/".length());
        }
        if (path.startsWith("/worker/")) {
            return "/%77orker/" + path.substring("/worker/".length());
        }
        throw new IllegalStateException("Route does not start with /internal/ or /worker/: " + path);
    }

    private static boolean isExecutionScoped(String pattern) {
        String[] s = segmentsOf(pattern);
        return s.length >= 5
                && (s[0].equals("internal") || s[0].equals("worker"))
                && s[1].equals("runs")
                && isVariable(s[2])
                && s[3].equals("node-executions")
                && isVariable(s[4]);
    }

    /** {@code pattern} split on {@code '/'}, with the leading empty element (before the first slash) dropped. */
    private static String[] segmentsOf(String pattern) {
        String[] raw = pattern.split("/");
        String[] result = new String[raw.length - 1];
        System.arraycopy(raw, 1, result, 0, result.length);
        return result;
    }

    private static boolean isVariable(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /** One audited route: its HTTP method, its route pattern, and whether the guard is in its handler chain. */
    public record Route(String method, String pattern, boolean guarded) {}

    /** Sends one probe request and reports back the response; the real implementation goes through {@link MockMvc}. */
    public interface Probe {
        Response send(String method, URI uri, String bearer);
    }

    /** A probe's response: the status code and the exact response body. */
    public record Response(int status, String body) {}
}

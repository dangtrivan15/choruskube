package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.architecture.RunScopeRouteAudit.Probe;
import com.choruskube.core.architecture.RunScopeRouteAudit.Response;
import com.choruskube.core.architecture.RunScopeRouteAudit.Route;
import com.choruskube.core.config.RunScopeInterceptor;
import com.choruskube.core.util.NodeExecutionUtil;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link RunScopeRouteAudit}'s own test, run over a fixed fake route list and a {@link Probe}
 * rather than a real Spring context — {@code RunScopeRoutesTest} is what proves the audit against
 * this repo's actual route table.
 */
class RunScopeRouteAuditTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID NODE_EXEC_ID = UUID.randomUUID();
    private static final UUID FOREIGN_RUN_ID = UUID.randomUUID();
    private static final String JOB_SECRET = "audit-test-job-secret";

    private static final String EXEC_INTERNAL = "/internal/runs/{runId}/node-executions/{nodeExecId}/decision";
    private static final String EXEC_WORKER = "/worker/runs/{runId}/node-executions/{nodeExecId}/status";
    private static final String RUN_LEVEL_INTERNAL = "/internal/runs/{runId}/status";
    private static final String RUN_LEVEL_WORKER = "/worker/runs/{runId}/github-token";

    /**
     * Simulates, for one probe request, what the real filter chain plus {@link RunScopeInterceptor}
     * would answer. Any id slot that isn't a real UUID (a fixed literal like {@code "latest"} in a
     * route the shape rule doesn't treat as execution-scoped) is left unparsed, the same as the real
     * filter's own regex never parses that slot either.
     */
    private static Response realisticProbe(String method, URI uri, String bearer) {
        String rawPath = uri.getRawPath();
        String decodedPath = uri.getPath();
        boolean escaped = rawPath.indexOf('%') >= 0;
        String[] segs = decodedPath.split("/");

        boolean workerFamily = segs.length > 1 && segs[1].equals("worker");
        boolean internalFamily = decodedPath.startsWith("/internal/");
        boolean execScopedShape = segs.length >= 6 && segs[2].equals("runs") && segs[4].equals("node-executions");

        if (escaped && (internalFamily || (workerFamily && execScopedShape))) {
            return new Response(400, RunScopeInterceptor.ESCAPED_PATH_MESSAGE);
        }
        if (execScopedShape) {
            try {
                UUID runId = UUID.fromString(segs[3]);
                UUID nodeExecId = UUID.fromString(segs[5]);
                if (!runId.equals(RUN_ID) || !nodeExecId.equals(NODE_EXEC_ID)) {
                    return new Response(
                            404, NodeExecutionUtil.notFound(nodeExecId).getMessage());
                }
                return new Response(200, "ok");
            } catch (IllegalArgumentException e) {
                // Falls through: this id slot is a fixed literal, not a real id.
            }
        }
        if (internalFamily) {
            return new Response(401, "unauthorized");
        }
        return new Response(200, "ok");
    }

    private static List<Route> baselineRoutes() {
        List<Route> routes = new ArrayList<>();
        routes.add(new Route("GET", EXEC_INTERNAL, true));
        routes.add(new Route("PUT", EXEC_WORKER, true));
        routes.add(new Route("PUT", RUN_LEVEL_INTERNAL, true));
        routes.add(new Route("GET", RUN_LEVEL_WORKER, true));
        return routes;
    }

    private static RunScopeRouteAudit auditOver(List<Route> routes, Probe probe) {
        return RunScopeRouteAudit.over(routes, probe)
                .executionInRun(RUN_ID, NODE_EXEC_ID, JOB_SECRET)
                .foreignRun(FOREIGN_RUN_ID);
    }

    @Test
    void wellBehavedRouteSet_passes() {
        assertThatCode(() -> auditOver(baselineRoutes(), RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .doesNotThrowAnyException();
    }

    @Test
    void routesIncludesEveryAuditedRoute() {
        assertThat(auditOver(baselineRoutes(), RunScopeRouteAuditTest::realisticProbe)
                        .routes())
                .containsExactlyInAnyOrder(
                        "GET " + EXEC_INTERNAL,
                        "PUT " + EXEC_WORKER,
                        "PUT " + RUN_LEVEL_INTERNAL,
                        "GET " + RUN_LEVEL_WORKER);
    }

    @Test
    void executionScopedRouteNotGuarded_fails() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.set(0, new Route("GET", EXEC_INTERNAL, false));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET " + EXEC_INTERNAL)
                .hasMessageContaining("is not in its handler chain");
    }

    @Test
    void executionScopedRouteServingAForeignPair_fails() {
        Probe brokenR1 = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            return real.status() == 404 ? new Response(200, "ok") : real;
        };

        assertThatThrownBy(() -> auditOver(baselineRoutes(), brokenR1).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not refuse a foreign pair");
    }

    @Test
    void internalRouteServingItsEscapedSpelling_fails() {
        Probe brokenR2 = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            return (real.status() == 400 && RunScopeInterceptor.ESCAPED_PATH_MESSAGE.equals(real.body()))
                    ? new Response(200, "ok")
                    : real;
        };

        assertThatThrownBy(() -> auditOver(baselineRoutes(), brokenR2).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not refuse its escaped spelling");
    }

    @Test
    void executionScopedWorkerRouteServingItsEscapedSpelling_fails() {
        Probe brokenR2 = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            return (real.status() == 400 && RunScopeInterceptor.ESCAPED_PATH_MESSAGE.equals(real.body()))
                    ? new Response(200, "ok")
                    : real;
        };

        // Isolate the worker route: a broken escape response on the /internal route would also
        // fail R2 there, muddying which route this test is about.
        List<Route> routes = baselineRoutes();
        Probe mixed = (method, uri, bearer) -> uri.getPath().startsWith("/worker/")
                ? brokenR2.send(method, uri, bearer)
                : realisticProbe(method, uri, bearer);

        assertThatThrownBy(() -> auditOver(routes, mixed).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PUT " + EXEC_WORKER)
                .hasMessageContaining("did not refuse its escaped spelling");
    }

    @Test
    void runLevelWorkerRouteServingItsEscapedSpelling_passes_becauseR2DoesNotApplyToIt() {
        // realisticProbe never returns 400 for a run-level /worker route's escaped spelling in the
        // first place (workerFamily && execScopedShape is false for it) — proving the point directly:
        // R2 is never even probed for this route, so its behaviour here cannot fail the audit.
        assertThatCode(() -> auditOver(baselineRoutes(), RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .doesNotThrowAnyException();
    }

    @Test
    void runLevelRouteAnsweringAgentSecretWithSomethingOtherThan401_fails() {
        Probe brokenR3 = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            if (uri.getPath().equals(expandRunLevel(RUN_ID)) && real.status() == 401) {
                return new Response(200, "ok");
            }
            return real;
        };

        assertThatThrownBy(() -> auditOver(baselineRoutes(), brokenR3).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not refuse an agent secret with 401");
    }

    private static String expandRunLevel(UUID runId) {
        return "/internal/runs/" + runId + "/status";
    }

    @Test
    void runLevelPatternWithMultiSegmentVariable_fails() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.add(new Route("GET", "/internal/{*path}", true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("multi-segment or regex-constrained path variable");
    }

    @Test
    void windowRuleAtIndexTwo_fails() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.add(new Route("GET", "/internal/x/{a}/{b}/{c}/{d}/{e}", true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("window at segment 2");
    }

    @Test
    void windowRuleAtIndexZero_literalLatestSegment_fails() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.add(new Route("GET", "/internal/runs/latest/node-executions/{id}", true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("window at segment 0");
    }

    @Test
    void windowRuleAtIndexZero_twoLeadingVariables_fails() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.add(new Route("GET", "/internal/{a}/{b}/node-executions/{c}", true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("window at segment 0");
    }

    @Test
    void currentShapes_fourSegmentListRoute_andGithubIssueClosedRoute_pass() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.add(new Route("GET", "/internal/runs/{runId}/node-executions", true));
        routes.add(new Route("POST", "/internal/tasks/{taskId}/github-issue/closed", true));

        assertThatCode(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .doesNotThrowAnyException();
    }

    @Test
    void emptyRouteSet_fails() {
        assertThatThrownBy(() -> auditOver(List.of(), RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no execution-scoped /internal route")
                .hasMessageContaining("no execution-scoped /worker route")
                .hasMessageContaining("no run-level /internal route");
    }

    @Test
    void missingOneRouteFamily_fails() {
        List<Route> routes = List.of(new Route("GET", EXEC_INTERNAL, true), new Route("PUT", RUN_LEVEL_INTERNAL, true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no execution-scoped /worker route");
    }

    @Test
    void neverCallingExecutionInRunOrForeignRun_fails() {
        assertThatThrownBy(() -> RunScopeRouteAudit.over(baselineRoutes(), RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("executionInRun(...) was never called")
                .hasMessageContaining("foreignRun(...) was never called");
    }

    @Test
    void multipleViolations_areAllReportedInOneAssertionError() {
        List<Route> routes = new ArrayList<>(baselineRoutes());
        routes.set(0, new Route("GET", EXEC_INTERNAL, false));
        routes.add(new Route("GET", "/internal/{*path}", true));

        assertThatThrownBy(() -> auditOver(routes, RunScopeRouteAuditTest::realisticProbe)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is not in its handler chain")
                .hasMessageContaining("multi-segment or regex-constrained path variable");
    }
}

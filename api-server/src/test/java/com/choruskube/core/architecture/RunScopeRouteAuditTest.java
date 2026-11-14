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
    private static final String ORCHESTRATOR_SECRET = "audit-test-orchestrator-secret";

    private static final String EXEC_INTERNAL = "/internal/runs/{runId}/node-executions/{nodeExecId}/decision";
    private static final String EXEC_WORKER = "/worker/runs/{runId}/node-executions/{nodeExecId}/status";
    private static final String RUN_LEVEL_INTERNAL = "/internal/runs/{runId}/status";
    private static final String RUN_LEVEL_WORKER = "/worker/runs/{runId}/github-token";

    /**
     * Simulates, for one probe request, what the real filter chain plus {@link RunScopeInterceptor}
     * would answer, with the filters deciding and authenticating on the decoded path:
     * {@code InternalAuthFilter} refuses with 401 unless the bearer is the orchestrator secret or
     * the job secret of the execution named at the fixed window, in the run named there too;
     * {@code WorkerAuthFilter} refuses with 401 only when no bearer is presented at all. Either way,
     * an escaped spelling reaches the same filter decision as the plain one — it is the guard
     * underneath, not the filter, that then refuses the escaped spelling itself.
     *
     * <p>Any id slot that isn't a real UUID (a fixed literal like {@code "latest"} in a route the
     * shape rule doesn't treat as execution-scoped) is left unparsed, the same as the real guard
     * never parses that slot either.
     */
    private static Response realisticProbe(String method, URI uri, String bearer) {
        String rawPath = uri.getRawPath();
        String decodedPath = uri.getPath();
        boolean escaped = rawPath.indexOf('%') >= 0;
        String[] segs = decodedPath.split("/");

        boolean workerFamily = segs.length > 1 && segs[1].equals("worker");
        boolean internalFamily = decodedPath.startsWith("/internal/");
        boolean execScopedShape = segs.length >= 6 && segs[2].equals("runs") && segs[4].equals("node-executions");

        if (internalFamily) {
            if (bearer == null) {
                return new Response(401, "unauthorized");
            }
            boolean orchestrator = bearer.equals(ORCHESTRATOR_SECRET);
            boolean agentOwnPair = !orchestrator
                    && bearer.equals(JOB_SECRET)
                    && execScopedShape
                    && segs[3].equals(RUN_ID.toString())
                    && segs[5].equals(NODE_EXEC_ID.toString());
            if (!orchestrator && !agentOwnPair) {
                return new Response(401, "unauthorized");
            }
            // Authenticated — fall through to the guard below.
        } else if (workerFamily) {
            if (bearer == null) {
                return new Response(401, "Missing fleet token");
            }
            // The Worker filter only proves a bearer was presented — fall through to the guard.
        } else {
            return new Response(200, "ok");
        }

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
                .foreignRun(FOREIGN_RUN_ID)
                .orchestratorSecret(ORCHESTRATOR_SECRET);
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
    void execInternalRouteAnsweringAgentsForeignPairWithGuard404InsteadOfFilter401_fails() {
        // Simulates a filter that authenticates the job secret without comparing the run, leaving
        // the mismatch to surface only as the guard's 404 underneath — the single-layer shape this
        // audit must catch, since the filter itself must refuse it.
        Probe filterNeverComparesRun = (method, uri, bearer) -> {
            String decodedPath = uri.getPath();
            String[] segs = decodedPath.split("/");
            boolean execInternalShape =
                    decodedPath.startsWith("/internal/") && segs.length >= 6 && segs[4].equals("node-executions");
            if (execInternalShape && JOB_SECRET.equals(bearer)) {
                UUID nodeExecId = UUID.fromString(segs[5]);
                if (NODE_EXEC_ID.equals(nodeExecId) && !RUN_ID.toString().equals(segs[3])) {
                    return new Response(
                            404, NodeExecutionUtil.notFound(nodeExecId).getMessage());
                }
            }
            return realisticProbe(method, uri, bearer);
        };

        assertThatThrownBy(() ->
                        auditOver(baselineRoutes(), filterNeverComparesRun).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET " + EXEC_INTERNAL)
                .hasMessageContaining("did not refuse its own execution under a foreign run with the filter's 401");
    }

    @Test
    void orchestratorForeignPairWithWrongGuardBody_fails() {
        Probe wrongBody = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            return (ORCHESTRATOR_SECRET.equals(bearer) && real.status() == 404)
                    ? new Response(404, "some other body")
                    : real;
        };

        assertThatThrownBy(() -> auditOver(baselineRoutes(), wrongBody).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not refuse the orchestrator's foreign pair with the guard's 404");
    }

    @Test
    void escapedSpellingWithNoCredential_anythingButFilter401_fails() {
        // Filters selecting on the raw URI skip an escaped prefix, so the request reaches the guard
        // unauthenticated and gets its 400 rather than a filter's 401 — on every family R2 covers.
        Probe filtersReadRawPath = (method, uri, bearer) -> uri.getRawPath().startsWith("/%")
                ? new Response(400, RunScopeInterceptor.ESCAPED_PATH_MESSAGE)
                : realisticProbe(method, uri, bearer);
        String noCredential = " did not refuse its escaped spelling with no credential";

        assertThatThrownBy(() -> auditOver(baselineRoutes(), filtersReadRawPath).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET " + EXEC_INTERNAL + noCredential)
                .hasMessageContaining("PUT " + RUN_LEVEL_INTERNAL + noCredential)
                .hasMessageContaining("PUT " + EXEC_WORKER + noCredential);
    }

    @Test
    void escapedSpellingWithValidCredential_anythingButExactGuard400_fails() {
        Probe wrongEscapeBody = (method, uri, bearer) -> {
            Response real = realisticProbe(method, uri, bearer);
            return (real.status() == 400 && RunScopeInterceptor.ESCAPED_PATH_MESSAGE.equals(real.body()))
                    ? new Response(400, "some other body")
                    : real;
        };

        assertThatThrownBy(() -> auditOver(baselineRoutes(), wrongEscapeBody).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not refuse its escaped spelling with a valid credential");
    }

    @Test
    void neverCallingOrchestratorSecret_fails() {
        assertThatThrownBy(() -> RunScopeRouteAudit.over(baselineRoutes(), RunScopeRouteAuditTest::realisticProbe)
                        .executionInRun(RUN_ID, NODE_EXEC_ID, JOB_SECRET)
                        .foreignRun(FOREIGN_RUN_ID)
                        .check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("orchestratorSecret(...) was never called");
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
        List<String> sentPaths = new ArrayList<>();
        Probe recording = (method, uri, bearer) -> {
            sentPaths.add(uri.getRawPath());
            return realisticProbe(method, uri, bearer);
        };

        // realisticProbe serves the run-level route's escaped spelling with 200, so R2 would fail
        // if it were applied there.
        assertThatCode(() -> auditOver(baselineRoutes(), recording).check()).doesNotThrowAnyException();
        assertThat(sentPaths).noneMatch(p -> p.startsWith("/%77orker/") && p.endsWith("/github-token"));
        assertThat(sentPaths).anyMatch(p -> p.startsWith("/%77orker/") && p.endsWith("/status"));
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

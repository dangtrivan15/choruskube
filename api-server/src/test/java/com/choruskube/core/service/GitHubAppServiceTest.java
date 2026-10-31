package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.credential.GitHubTokenScope;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubTokenMintException;
import com.choruskube.core.exception.GitHubTokenScopeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class GitHubAppServiceTest {

    private final GitHubAppService service = new GitHubAppService(new ObjectMapper(), "https://api.github.com");

    @Test
    void createSignedJwt_producesValidJwtWithCorrectClaims() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        String base64Key = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----\n";
        String appId = "99999";

        String jwtString = service.createSignedJwt(appId, pem);
        SignedJWT parsed = SignedJWT.parse(jwtString);

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo(appId);
        assertThat(parsed.getJWTClaimsSet().getIssueTime()).isNotNull();
        assertThat(parsed.getJWTClaimsSet().getExpirationTime()).isNotNull();
        assertThat(parsed.getJWTClaimsSet().getExpirationTime())
                .isAfter(parsed.getJWTClaimsSet().getIssueTime());
    }

    @Test
    void createSignedJwt_withMalformedPem_throws() {
        assertThatThrownBy(() -> service.createSignedJwt("12345", "not a real pem"))
                .isInstanceOf(Exception.class);
    }

    // -----------------------------------------------------------------------------------
    // Over real HTTP — the status has to survive the client, and the body must not
    // -----------------------------------------------------------------------------------

    /**
     * The status is what the caller classifies on — a 401 disengages the Autopilot where a 503 does
     * not — so it has to arrive as a field rather than as text somebody has to parse back out.
     *
     * <p>The body must not arrive at all. GitHub echoes the request in some error payloads, the
     * {@code Authorization} header among them, and this message is logged and rendered in the UI.
     * The stub answers with exactly that shape, so the assertion is about a real payload rather than
     * a hypothetical one.
     */
    @Test
    void createIssue_unauthorized_carriesTheStatusAndNeverTheBody() throws Exception {
        String echoingBody = "{\"message\":\"Bad credentials\","
                + "\"request\":{\"headers\":{\"Authorization\":\"Bearer ghs_notatoken\"}}}";
        try (Stub github = Stub.serving(401, echoingBody)) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.createIssue("ghs_notatoken", "org/backend-api", "Follow-up", "body"))
                    .isInstanceOf(GitHubApiException.class)
                    .hasMessage("GitHub returned 401 for org/backend-api")
                    .satisfies(thrown -> {
                        GitHubApiException e = (GitHubApiException) thrown;
                        assertThat(e.getStatus()).isEqualTo(401);
                        assertThat(e.getOwnerRepo()).isEqualTo("org/backend-api");
                        assertThat(e.getMessage()).doesNotContain("ghs_notatoken", "Bad credentials");
                    });
        }
    }

    @Test
    void createIssue_serverError_carriesTheStatusSoTheCallerCanCallItTransient() throws Exception {
        try (Stub github = Stub.serving(503, "unavailable")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.createIssue("t0ken", "org/backend-api", "Follow-up", "body"))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(503));
        }
    }

    /**
     * A rate limit on the mint used to throw a bare {@code RuntimeException} with the status only
     * interpolated into its text — indistinguishable from a private key that will not parse, so every
     * credential failure stopped the Autopilot.
     */
    @Test
    void generateInstallationToken_rateLimited_throwsATypedExceptionCarryingTheHints() throws Exception {
        try (Stub github = Stub.serving(403, "{\"message\":\"rate limited\"}", Map.of("retry-after", "30"))) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "12345", testPrivateKeyPem(), scope))
                    .isInstanceOf(GitHubTokenMintException.class)
                    .satisfies(thrown -> {
                        GitHubTokenMintException e = (GitHubTokenMintException) thrown;
                        assertThat(e.getStatus()).isEqualTo(403);
                        assertThat(e.getRateLimitHints().indicatesRateLimit()).isTrue();
                        assertThat(e.getRateLimitHints().retryAfterSeconds()).isEqualTo(30);
                        assertThat(e.getMessage()).contains("12345").doesNotContain("rate limited");
                    });
        }
    }

    @Test
    void generateInstallationToken_exhaustedPrimaryQuota_carriesRemainingAndReset() throws Exception {
        Map<String, String> headers = Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1800000000");
        try (Stub github = Stub.serving(403, "{}", headers)) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "12345", testPrivateKeyPem(), scope))
                    .satisfies(thrown -> {
                        GitHubRateLimitHints hints = ((GitHubTokenMintException) thrown).getRateLimitHints();
                        assertThat(hints.remaining()).isZero();
                        assertThat(hints.resetEpochSeconds()).isEqualTo(1_800_000_000L);
                        assertThat(hints.indicatesRateLimit()).isTrue();
                    });
        }
    }

    /**
     * A 403 with quota to spare is a permissions problem, and must keep stopping the Autopilot. The
     * hints are a positive signal only — this is the assertion that keeps them from becoming an
     * escape hatch that silently retries a revoked credential forever.
     */
    @Test
    void generateInstallationToken_forbiddenWithQuotaRemaining_doesNotLookLikeARateLimit() throws Exception {
        try (Stub github = Stub.serving(403, "{}", Map.of("x-ratelimit-remaining", "4999"))) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "12345", testPrivateKeyPem(), scope))
                    .satisfies(thrown -> assertThat(((GitHubTokenMintException) thrown)
                                    .getRateLimitHints()
                                    .indicatesRateLimit())
                            .isFalse());
        }
    }

    /**
     * A header GitHub changed the shape of must not turn a classification problem into a crash
     * inside a reconciler. Unparsable reads as absent, which classifies the response exactly as it
     * was classified before any of this existed.
     */
    @Test
    void generateInstallationToken_malformedRateLimitHeaders_areIgnoredRatherThanThrowing() throws Exception {
        Map<String, String> headers =
                Map.of("retry-after", "Fri, 01 Jan 2027 00:00:00 GMT", "x-ratelimit-remaining", "");
        try (Stub github = Stub.serving(403, "{}", headers)) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "12345", testPrivateKeyPem(), scope))
                    .isInstanceOf(GitHubTokenMintException.class)
                    .satisfies(thrown -> {
                        GitHubRateLimitHints hints = ((GitHubTokenMintException) thrown).getRateLimitHints();
                        assertThat(hints.retryAfterSeconds()).isNull();
                        assertThat(hints.remaining()).isNull();
                        assertThat(hints.indicatesRateLimit()).isFalse();
                    });
        }
    }

    /** A real RSA key, so the JWT is signed before the stubbed response is ever reached. */
    private static String testPrivateKeyPem() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        RSAPrivateKey privateKey = (RSAPrivateKey) keyGen.generateKeyPair().getPrivate();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    // -----------------------------------------------------------------------------------
    // compareCommits / deleteRef — the branch-ref primitives BranchCleanupService uses
    // -----------------------------------------------------------------------------------

    @Test
    void compareCommits_ok_parsesAheadByAndHitsTheRightRequest() throws Exception {
        try (Stub github = Stub.serving(200, "{\"ahead_by\":3,\"status\":\"ahead\"}")) {
            int ahead = github.service().compareCommits("t0ken", "org/backend-api", "main", "choruskube-run-abc");

            assertThat(ahead).isEqualTo(3);
            assertThat(github.captured().method).isEqualTo("GET");
            assertThat(github.captured().path).isEqualTo("/repos/org/backend-api/compare/main...choruskube-run-abc");
            assertThat(github.captured().authorization).isEqualTo("Bearer t0ken");
        }
    }

    @Test
    void compareCommits_zeroAheadBy_parsesAsZero() throws Exception {
        try (Stub github = Stub.serving(200, "{\"ahead_by\":0,\"status\":\"identical\"}")) {
            assertThat(github.service().compareCommits("t0ken", "org/backend-api", "main", "stale-branch"))
                    .isZero();
        }
    }

    /**
     * A 404 (branch or base not found) has to arrive with the real status intact — it's what lets
     * {@code BranchCleanupService} tell "gone" apart from any other failure via {@link
     * GitHubApiException#getStatus()}.
     */
    @Test
    void compareCommits_404_throwsWithTheRealStatus() throws Exception {
        try (Stub github = Stub.serving(404, "{\"message\":\"Not Found\"}")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.compareCommits("t0ken", "org/backend-api", "main", "gone-branch"))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(404));
        }
    }

    @Test
    void compareCommits_serverError_throwsWithTheRealStatus() throws Exception {
        try (Stub github = Stub.serving(500, "unavailable")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.compareCommits("t0ken", "org/backend-api", "main", "branch"))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(500));
        }
    }

    @Test
    void deleteRef_noContent_returnsTrueAndHitsTheRightRequest() throws Exception {
        try (Stub github = Stub.serving(204, "")) {
            boolean deleted = github.service().deleteRef("t0ken", "org/backend-api", "heads/choruskube-run-abc");

            assertThat(deleted).isTrue();
            assertThat(github.captured().method).isEqualTo("DELETE");
            assertThat(github.captured().path).isEqualTo("/repos/org/backend-api/git/refs/heads/choruskube-run-abc");
            assertThat(github.captured().authorization).isEqualTo("Bearer t0ken");
        }
    }

    @Test
    void deleteRef_404_returnsTrueBecauseItIsAlreadyGone() throws Exception {
        try (Stub github = Stub.serving(404, "{\"message\":\"Not Found\"}")) {
            assertThat(github.service().deleteRef("t0ken", "org/backend-api", "heads/already-gone"))
                    .isTrue();
        }
    }

    @Test
    void deleteRef_unprocessable_returnsTrueBecauseItIsAlreadyGone() throws Exception {
        try (Stub github = Stub.serving(422, "{\"message\":\"Reference does not exist\"}")) {
            assertThat(github.service().deleteRef("t0ken", "org/backend-api", "heads/already-gone"))
                    .isTrue();
        }
    }

    @Test
    void deleteRef_serverError_throwsWithTheRealStatus() throws Exception {
        try (Stub github = Stub.serving(500, "unavailable")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.deleteRef("t0ken", "org/backend-api", "heads/branch"))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(500));
        }
    }

    @Test
    void createIssue_created_returnsNumberAndUrl() throws Exception {
        try (Stub github =
                Stub.serving(201, "{\"number\":42,\"html_url\":\"https://github.com/org/backend-api/issues/42\"}")) {
            var issue = github.service().createIssue("t0ken", "org/backend-api", "Follow-up", "body text");

            assertThat(issue.number()).isEqualTo(42);
            assertThat(issue.htmlUrl()).isEqualTo("https://github.com/org/backend-api/issues/42");
            assertThat(github.captured().method).isEqualTo("POST");
            assertThat(github.captured().path).isEqualTo("/repos/org/backend-api/issues");
            assertThat(github.captured().authorization).isEqualTo("Bearer t0ken");
            assertThat(new ObjectMapper().readTree(github.captured().body))
                    .isEqualTo(new ObjectMapper().readTree("{\"title\":\"Follow-up\",\"body\":\"body text\"}"));
        }
    }

    @Test
    void createIssue_nonTwoxx_throwsGitHubApiException() throws Exception {
        try (Stub github = Stub.serving(422, "{\"message\":\"Validation Failed\"}")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.createIssue("t0ken", "org/backend-api", "Follow-up", "body"))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(422));
        }
    }

    /** What one request to the stub actually looked like — set exactly once, before the response. */
    private static final class CapturedRequest {
        volatile String method;
        volatile String path;
        volatile String authorization;
        volatile String body;
    }

    /** A GitHub that answers with one canned response, on a port the OS picks. */
    private record Stub(HttpServer server, CapturedRequest captured) implements AutoCloseable {

        static Stub serving(int status, String body) throws IOException {
            return serving(status, body, Map.of());
        }

        static Stub serving(int status, String body, Map<String, String> headers) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CapturedRequest captured = new CapturedRequest();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                captured.method = exchange.getRequestMethod();
                captured.path = exchange.getRequestURI().toString();
                captured.authorization = exchange.getRequestHeaders().getFirst("Authorization");
                captured.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return new Stub(server, captured);
        }

        GitHubAppService service() {
            return new GitHubAppService(
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------------------
    // generateInstallationToken(scope) / installation() / verifyAppCredential — narrowing,
    // the installation cache, and the 422 contract.
    // -----------------------------------------------------------------------------------

    private static final String INSTALLATION_BODY = "{\"account\":{\"login\":\"acme\"},\"permissions\":{"
            + "\"metadata\":\"read\",\"contents\":\"write\",\"pull_requests\":\"write\",\"issues\":\"write\","
            + "\"workflows\":\"write\",\"actions\":\"read\",\"checks\":\"read\",\"statuses\":\"read\","
            + "\"administration\":\"write\"}}";

    @Test
    void generateInstallationToken_recordedBody_isNarrowed_dropsForeignRepoAndAdministration() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api", "other/tools"));

            String token = github.service().generateInstallationToken("app-id", "67890", testPrivateKeyPem(), scope);

            assertThat(token).isEqualTo("ghs_test");
            // Exact string, not JSON-equivalence: field order and sorted permission keys are part
            // of the mint-body contract the Worker's Go client sends too.
            assertThat(github.postRequests()).singleElement().satisfies(r -> {
                assertThat(r.body)
                        .isEqualTo("{\"repositories\":[\"api\"],\"permissions\":{"
                                + "\"actions\":\"read\",\"checks\":\"read\",\"contents\":\"write\","
                                + "\"issues\":\"write\",\"metadata\":\"read\",\"pull_requests\":\"write\","
                                + "\"statuses\":\"read\",\"workflows\":\"write\"}}");
                assertThat(r.authorization).startsWith("Bearer ");
            });
            assertThat(github.getRequests()).singleElement().satisfies(r -> assertThat(r.authorization)
                    .startsWith("Bearer "));
        }
    }

    @Test
    void generateInstallationToken_twoMints_shareOneInstallationLookup() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
            String pem = testPrivateKeyPem();

            service.generateInstallationToken("app-id", "67890", pem, scope);
            service.generateInstallationToken("app-id", "67890", pem, scope);

            assertThat(github.getRequests()).hasSize(1);
            assertThat(github.postRequests()).hasSize(2);
        }
    }

    @Test
    void generateInstallationToken_afterTheCacheWindow_looksTheInstallationUpAgain() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
            GitHubAppService service = github.service(clock);
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
            String pem = testPrivateKeyPem();

            service.generateInstallationToken("app-id", "67890", pem, scope);
            clock.advance(Duration.ofMinutes(9));
            service.generateInstallationToken("app-id", "67890", pem, scope);
            assertThat(github.getRequests()).hasSize(1);

            clock.advance(Duration.ofMinutes(2));
            service.generateInstallationToken("app-id", "67890", pem, scope);
            assertThat(github.getRequests()).hasSize(2);
        }
    }

    @Test
    void generateInstallationToken_sameInstallationId_differentPem_getsItsOwnLookup() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));

            service.generateInstallationToken("app-id", "67890", testPrivateKeyPem(), scope);
            service.generateInstallationToken("app-id", "67890", testPrivateKeyPem(), scope);

            assertThat(github.getRequests()).hasSize(2);
        }
    }

    @Test
    void generateInstallationToken_422_dropsCacheAndNamesTheRequestedRepos_nextMintRefetches() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
            String pem = testPrivateKeyPem();
            github.queuePost(422, "{\"message\":\"Validation Failed\"}");

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "67890", pem, scope))
                    .isInstanceOf(GitHubTokenScopeException.class)
                    .hasMessageContaining("acme/api")
                    .hasMessageContaining("acme")
                    .satisfies(thrown -> assertThat(thrown.getCause())
                            .isInstanceOf(GitHubTokenMintException.class)
                            .satisfies(cause -> assertThat(((GitHubTokenMintException) cause).getStatus())
                                    .isEqualTo(422)));

            service.generateInstallationToken("app-id", "67890", pem, scope);

            assertThat(github.getRequests()).hasSize(2);
        }
    }

    @Test
    void generateInstallationToken_emptyScope_refusesBeforeAnyCall() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubTokenScope empty = GitHubTokenScope.of(List.of());

            assertThatThrownBy(() ->
                            github.service().generateInstallationToken("app-id", "67890", testPrivateKeyPem(), empty))
                    .isInstanceOf(GitHubTokenScopeException.class);

            assertThat(github.getRequests()).isEmpty();
            assertThat(github.postRequests()).isEmpty();
        }
    }

    @Test
    void generateInstallationToken_allForeignScope_throwsScopeException_makesNoPost() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("other/tools"));

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "67890", testPrivateKeyPem(), scope))
                    .isInstanceOf(GitHubTokenScopeException.class);

            assertThat(github.postRequests()).isEmpty();
        }
    }

    @Test
    void verifyAppCredential_sendsTheMetadataOnlyBody() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            github.service().verifyAppCredential("app-id", "67890", testPrivateKeyPem());

            assertThat(github.postRequests()).singleElement().satisfies(r -> assertThat(r.body)
                    .isEqualToIgnoringWhitespace("{\"permissions\":{\"metadata\":\"read\"}}"));
        }
    }

    @Test
    void verifyAppCredential_non201_throwsGitHubTokenMintExceptionNamingTheStatus() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            github.queuePost(401, "{\"message\":\"Bad credentials\"}");

            assertThatThrownBy(() -> github.service().verifyAppCredential("app-id", "67890", testPrivateKeyPem()))
                    .isInstanceOf(GitHubTokenMintException.class)
                    .hasMessageContaining("401")
                    .satisfies(thrown -> assertThat(((GitHubTokenMintException) thrown).getStatus())
                            .isEqualTo(401));
            assertThat(github.getRequests()).isEmpty();
        }
    }

    @Test
    void installationLookup_non200_throwsGitHubTokenMintException() throws Exception {
        try (ScopedStub github = ScopedStub.create()) {
            GitHubAppService service = github.service();
            GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
            github.queueGet(401, "{\"message\":\"Bad credentials\"}");

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "67890", testPrivateKeyPem(), scope))
                    .isInstanceOf(GitHubTokenMintException.class)
                    .satisfies(thrown -> assertThat(((GitHubTokenMintException) thrown).getStatus())
                            .isEqualTo(401));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** What one request to the stub actually looked like, captured as it arrived. */
    private static final class RoutedRequest {
        final String method;
        final String path;
        final String authorization;
        final String body;

        RoutedRequest(String method, String path, String authorization, String body) {
            this.method = method;
            this.path = path;
            this.authorization = authorization;
            this.body = body;
        }
    }

    private static final class ScopedResponse {
        final int status;
        final String body;

        ScopedResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * A GitHub stub that answers the installation {@code GET} and the mint {@code POST}
     * independently, each from its own queue of canned responses (falling back to a default once
     * the queue is drained) — needed because a mint narrows against the installation lookup, so a
     * single-response stub can't exercise both calls with different outcomes.
     */
    private static final class ScopedStub implements AutoCloseable {
        private final HttpServer server;
        private final List<RoutedRequest> getRequests = new CopyOnWriteArrayList<>();
        private final List<RoutedRequest> postRequests = new CopyOnWriteArrayList<>();
        private final Deque<ScopedResponse> getQueue = new ArrayDeque<>();
        private final Deque<ScopedResponse> postQueue = new ArrayDeque<>();

        private ScopedStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().toString();
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RoutedRequest captured = new RoutedRequest(method, path, authorization, body);
                ScopedResponse response;
                synchronized (this) {
                    if ("GET".equals(method)) {
                        getRequests.add(captured);
                        response =
                                getQueue.isEmpty() ? new ScopedResponse(200, INSTALLATION_BODY) : getQueue.pollFirst();
                    } else {
                        postRequests.add(captured);
                        response = postQueue.isEmpty()
                                ? new ScopedResponse(201, "{\"token\":\"ghs_test\"}")
                                : postQueue.pollFirst();
                    }
                }
                byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        static ScopedStub create() throws IOException {
            return new ScopedStub();
        }

        void queueGet(int status, String body) {
            synchronized (this) {
                getQueue.addLast(new ScopedResponse(status, body));
            }
        }

        void queuePost(int status, String body) {
            synchronized (this) {
                postQueue.addLast(new ScopedResponse(status, body));
            }
        }

        List<RoutedRequest> getRequests() {
            return getRequests;
        }

        List<RoutedRequest> postRequests() {
            return postRequests;
        }

        GitHubAppService service() {
            return service(Clock.systemUTC());
        }

        GitHubAppService service(Clock clock) {
            return new GitHubAppService(
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    clock);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

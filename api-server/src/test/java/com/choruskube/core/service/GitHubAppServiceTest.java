package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubTokenMintException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
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

    @Test
    void parsePullRequest_openPr_hasNullMergedAt() {
        var snapshot = service.parsePullRequest("{\"state\":\"open\",\"merged_at\":null}");

        assertThat(snapshot.state()).isEqualTo("open");
        assertThat(snapshot.mergedAt()).isNull();
    }

    @Test
    void parsePullRequest_mergedPr_parsesMergedAt() {
        var snapshot = service.parsePullRequest("{\"state\":\"closed\",\"merged_at\":\"2026-08-16T10:00:00Z\"}");

        assertThat(snapshot.state()).isEqualTo("closed");
        assertThat(snapshot.mergedAt()).isEqualTo(Instant.parse("2026-08-16T10:00:00Z"));
    }

    @Test
    void parsePullRequest_closedUnmergedPr_hasNullMergedAt() {
        var snapshot = service.parsePullRequest("{\"state\":\"closed\",\"merged_at\":null}");

        assertThat(snapshot.state()).isEqualTo("closed");
        assertThat(snapshot.mergedAt()).isNull();
    }

    @Test
    void parsePullRequest_missingMergedAtField_hasNullMergedAt() {
        var snapshot = service.parsePullRequest("{\"state\":\"open\"}");

        assertThat(snapshot.mergedAt()).isNull();
    }

    // -----------------------------------------------------------------------------------
    // Over real HTTP — the status has to survive the client, and the body must not
    // -----------------------------------------------------------------------------------

    @Test
    void fetchPullRequest_ok_readsTheSnapshot() throws Exception {
        try (Stub github = Stub.serving(200, "{\"state\":\"closed\",\"merged_at\":\"2026-08-16T10:00:00Z\"}")) {
            var snapshot = github.service().fetchPullRequest("t0ken", "org/backend-api", 42);

            assertThat(snapshot.state()).isEqualTo("closed");
            assertThat(snapshot.mergedAt()).isEqualTo(Instant.parse("2026-08-16T10:00:00Z"));
        }
    }

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
    void fetchPullRequest_unauthorized_carriesTheStatusAndNeverTheBody() throws Exception {
        String echoingBody = "{\"message\":\"Bad credentials\","
                + "\"request\":{\"headers\":{\"Authorization\":\"Bearer ghs_notatoken\"}}}";
        try (Stub github = Stub.serving(401, echoingBody)) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("ghs_notatoken", "org/backend-api", 42))
                    .isInstanceOf(GitHubApiException.class)
                    .hasMessage("GitHub returned 401 for org/backend-api#42")
                    .satisfies(thrown -> {
                        GitHubApiException e = (GitHubApiException) thrown;
                        assertThat(e.getStatus()).isEqualTo(401);
                        assertThat(e.getOwnerRepo()).isEqualTo("org/backend-api");
                        assertThat(e.getPrNumber()).isEqualTo(42);
                        assertThat(e.getMessage()).doesNotContain("ghs_notatoken", "Bad credentials");
                    });
        }
    }

    @Test
    void fetchPullRequest_serverError_carriesTheStatusSoTheCallerCanCallItTransient() throws Exception {
        try (Stub github = Stub.serving(503, "unavailable")) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("t0ken", "org/backend-api", 42))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown).getStatus())
                            .isEqualTo(503));
        }
    }

    /**
     * The whole point of reading the headers: a 403 is the status GitHub uses both for a credential
     * that lacks access and for a secondary rate limit, so only {@code retry-after} separates "wait"
     * from "a human must act". Against a real HTTP server, not a mocked response, because the
     * question is whether the client reads a header off the wire at all.
     */
    @Test
    void fetchPullRequest_rateLimited403_carriesTheRetryAfterHeader() throws Exception {
        try (Stub github = Stub.serving(403, "{\"message\":\"rate limited\"}", Map.of("retry-after", "60"))) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("t0ken", "org/backend-api", 42))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> {
                        GitHubApiException e = (GitHubApiException) thrown;
                        assertThat(e.getStatus()).isEqualTo(403);
                        assertThat(e.getRateLimitHints().retryAfterSeconds()).isEqualTo(60);
                        assertThat(e.getRateLimitHints().indicatesRateLimit()).isTrue();
                    });
        }
    }

    @Test
    void fetchPullRequest_exhaustedPrimaryQuota_carriesRemainingAndReset() throws Exception {
        Map<String, String> headers = Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1800000000");
        try (Stub github = Stub.serving(403, "{}", headers)) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("t0ken", "org/backend-api", 42))
                    .satisfies(thrown -> {
                        GitHubRateLimitHints hints = ((GitHubApiException) thrown).getRateLimitHints();
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
    void fetchPullRequest_forbiddenWithQuotaRemaining_doesNotLookLikeARateLimit() throws Exception {
        try (Stub github = Stub.serving(403, "{}", Map.of("x-ratelimit-remaining", "4999"))) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("t0ken", "org/backend-api", 42))
                    .satisfies(thrown -> assertThat(((GitHubApiException) thrown)
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
    void fetchPullRequest_malformedRateLimitHeaders_areIgnoredRatherThanThrowing() throws Exception {
        Map<String, String> headers =
                Map.of("retry-after", "Fri, 01 Jan 2027 00:00:00 GMT", "x-ratelimit-remaining", "");
        try (Stub github = Stub.serving(403, "{}", headers)) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.fetchPullRequest("t0ken", "org/backend-api", 42))
                    .isInstanceOf(GitHubApiException.class)
                    .satisfies(thrown -> {
                        GitHubRateLimitHints hints = ((GitHubApiException) thrown).getRateLimitHints();
                        assertThat(hints.retryAfterSeconds()).isNull();
                        assertThat(hints.remaining()).isNull();
                        assertThat(hints.indicatesRateLimit()).isFalse();
                    });
        }
    }

    /**
     * Minting is the other path a rate limit arrives on, and it used to throw a bare
     * {@code RuntimeException} with the status only interpolated into its text — indistinguishable
     * from a private key that will not parse, so every credential failure stopped the Autopilot.
     */
    @Test
    void generateInstallationToken_rateLimited_throwsATypedExceptionCarryingTheHints() throws Exception {
        try (Stub github = Stub.serving(403, "{\"message\":\"rate limited\"}", Map.of("retry-after", "30"))) {
            GitHubAppService service = github.service();

            assertThatThrownBy(() -> service.generateInstallationToken("app-id", "12345", testPrivateKeyPem()))
                    .isInstanceOf(GitHubTokenMintException.class)
                    .satisfies(thrown -> {
                        GitHubTokenMintException e = (GitHubTokenMintException) thrown;
                        assertThat(e.getStatus()).isEqualTo(403);
                        assertThat(e.getRateLimitHints().indicatesRateLimit()).isTrue();
                        assertThat(e.getMessage()).contains("12345").doesNotContain("rate limited");
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
     * GitHubApiException#getStatus()}, the same convention {@code PullRequestStateService} already
     * relies on.
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

    /** What one request to the stub actually looked like — set exactly once, before the response. */
    private static final class CapturedRequest {
        volatile String method;
        volatile String path;
        volatile String authorization;
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
}

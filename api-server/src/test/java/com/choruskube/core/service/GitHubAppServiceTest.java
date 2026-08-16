package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.exception.GitHubApiException;
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

    /** A GitHub that answers with one canned response, on a port the OS picks. */
    private record Stub(HttpServer server) implements AutoCloseable {

        static Stub serving(int status, String body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return new Stub(server);
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

package com.choruskube.core.service;

import com.choruskube.core.exception.GitHubApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitHubAppService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String githubApiUrl;

    public GitHubAppService(
            ObjectMapper objectMapper, @Value("${github.api.url:https://api.github.com}") String githubApiUrl) {
        this.objectMapper = objectMapper;
        this.githubApiUrl = githubApiUrl;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String generateInstallationToken(String appId, String installationId, String privateKeyPem) {
        try {
            String jwt = createSignedJwt(appId, privateKeyPem);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(githubApiUrl + "/app/installations/" + installationId + "/access_tokens"))
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                // The body is deliberately omitted, for the reason given on fetchPullRequest: a
                // GitHub error payload can echo the request, Authorization header included, and
                // this message is logged.
                throw new RuntimeException("GitHub API returned " + response.statusCode()
                        + " minting an installation token for installation " + installationId);
            }

            JsonNode body = objectMapper.readTree(response.body());
            return body.get("token").asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while generating GitHub installation token", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate GitHub installation token", e);
        }
    }

    /** A pull request's state as GitHub reports it. {@code mergedAt} is null unless merged. */
    public record PullRequestSnapshot(String state, Instant mergedAt) {}

    /**
     * Reads a pull request's state. {@code ownerRepo} is the {@code owner/repo} slug — derive it
     * with {@link com.choruskube.core.util.RepoNameUtil#deriveOwnerRepoName(String)} from the
     * {@code GitRepo}'s URL. The caller supplies an already-resolved token, so this method stays
     * agnostic about whether it came from a GitHub App installation or a PAT.
     *
     * @throws GitHubApiException if GitHub returns a non-200 status. It carries the status as a
     *     field so a caller can tell a revoked credential from an outage; the message deliberately
     *     omits the response body, which can echo the request's Authorization header on some
     *     errors.
     * @throws RuntimeException if the call never produced a status at all — a timeout, a DNS
     *     failure, a reset connection. Deliberately NOT a {@link GitHubApiException}: there is no
     *     status to classify, and every such failure is transient by nature.
     */
    public PullRequestSnapshot fetchPullRequest(String token, String ownerRepo, int prNumber) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/repos/" + ownerRepo + "/pulls/" + prNumber))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new GitHubApiException(response.statusCode(), ownerRepo, prNumber);
            }
            return parsePullRequest(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to read " + ownerRepo + "#" + prNumber + ": " + e.getMessage(), e);
        }
    }

    PullRequestSnapshot parsePullRequest(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode mergedAt = node.get("merged_at");
            return new PullRequestSnapshot(
                    node.path("state").asText(null),
                    mergedAt == null || mergedAt.isNull() ? null : Instant.parse(mergedAt.asText()));
        } catch (Exception e) {
            // Deliberately does not interpolate the exception's message: it can quote the response
            // content that failed to parse. Harmless for a timestamp, but this is a pattern worth
            // not leaving around to be copied onto a field that carries something sensitive. The
            // cause is chained, so the detail is still available to a debugger and to logs.
            throw new RuntimeException("Failed to parse GitHub pull request payload", e);
        }
    }

    String createSignedJwt(String appId, String privateKeyPem) throws Exception {
        String base64Key = privateKeyPem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(appId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600))) // 10 minutes
                .build();

        SignedJWT signedJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        signedJwt.sign(new RSASSASigner(privateKey));

        return signedJwt.serialize();
    }
}

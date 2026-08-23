package com.choruskube.core.service;

import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubTokenMintException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
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
                // this message is logged. The rate-limit headers are read instead — minting is the
                // other path a secondary rate limit arrives on, and a bare RuntimeException here
                // used to make every such failure indistinguishable from an unreadable key.
                throw new GitHubTokenMintException(
                        response.statusCode(), installationId, rateLimitHints(response.headers()));
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
                throw new GitHubApiException(
                        response.statusCode(), ownerRepo, prNumber, rateLimitHints(response.headers()));
            }
            return parsePullRequest(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to read " + ownerRepo + "#" + prNumber + ": " + e.getMessage(), e);
        }
    }

    /**
     * How many commits {@code head} is ahead of {@code base} — GitHub's {@code compare}
     * {@code ahead_by} field. {@code base}/{@code head} are branch (or ref) names, not SHAs;
     * {@code ownerRepo} is the {@code owner/repo} slug (see {@link #fetchPullRequest} for how to
     * derive it). Used by {@code BranchCleanupService} to decide whether a run branch is safe to
     * delete: {@code ahead_by == 0} means the branch carries nothing the default branch lacks.
     *
     * @throws GitHubApiException if GitHub returns a non-2xx status — notably 404 when either ref
     *     is not found, which the caller distinguishes from any other failure via {@link
     *     GitHubApiException#getStatus()}, the same convention {@code PullRequestStateService}
     *     already relies on
     * @throws RuntimeException if the response body cannot be parsed, or if the call never produced
     *     a status at all (timeout, DNS failure, reset connection)
     */
    public int compareCommits(String token, String ownerRepo, String base, String head) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/repos/" + ownerRepo + "/compare/" + base + "..." + head))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GitHubApiException(response.statusCode(), ownerRepo, rateLimitHints(response.headers()));
            }
            try {
                JsonNode body = objectMapper.readTree(response.body());
                return body.get("ahead_by").asInt();
            } catch (Exception e) {
                // Same reasoning as parsePullRequest: the exception's own message can quote the
                // response body that failed to parse, so it is deliberately not interpolated here.
                throw new RuntimeException("Failed to parse GitHub compare payload for " + ownerRepo, e);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(
                    "Failed to compare " + base + "..." + head + " for " + ownerRepo + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a ref — {@code caller passes "heads/" + branch}. Returns {@code true} both when the
     * delete succeeded (2xx) and when the ref was already gone (404/422): both leave the ref absent,
     * which is all {@code BranchCleanupService} cares about.
     *
     * @throws GitHubApiException on any other non-2xx status, carrying the real status code
     * @throws RuntimeException if the call never produced a status at all
     */
    public boolean deleteRef(String token, String ownerRepo, String ref) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/repos/" + ownerRepo + "/git/refs/" + ref))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2 || status == 404 || status == 422) {
                return true;
            }
            throw new GitHubApiException(status, ownerRepo, rateLimitHints(response.headers()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to delete ref " + ref + " for " + ownerRepo + ": " + e.getMessage(), e);
        }
    }

    /**
     * The three rate-limit headers, parsed. The only thing kept from a failed response.
     *
     * <p>Every parse is total: a missing, blank or malformed value becomes null rather than an
     * exception. A header GitHub changed the format of must not be able to turn a classification
     * problem into a crash inside a reconciler, and null is already the answer that means "no
     * signal", which classifies the response the same way it was classified before this existed.
     *
     * <p>{@code HttpHeaders} lookup is case-insensitive, so the lower-case names here match
     * whatever casing the response used.
     */
    private static GitHubRateLimitHints rateLimitHints(HttpHeaders headers) {
        return new GitHubRateLimitHints(
                parseInt(headers, "retry-after"),
                parseInt(headers, "x-ratelimit-remaining"),
                parseLong(headers, "x-ratelimit-reset"));
    }

    private static Integer parseInt(HttpHeaders headers, String name) {
        Long value = parseLong(headers, name);
        return value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? null : value.intValue();
    }

    private static Long parseLong(HttpHeaders headers, String name) {
        try {
            return headers.firstValue(name)
                    .map(String::trim)
                    .map(Long::parseLong)
                    .orElse(null);
        } catch (NumberFormatException e) {
            // Deliberately not interpolated into the log: the value is attacker-adjacent input and
            // this runs on every failed GitHub call.
            return null;
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

package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubTokenScope;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubMergeRefusedException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubTokenMintException;
import com.choruskube.core.exception.GitHubTokenScopeException;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitHubAppService {

    /** How long an installation lookup is trusted before a mint re-fetches it. */
    private static final Duration INSTALLATION_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String githubApiUrl;
    private final Clock clock;

    /** Keyed by {@link #fingerprint}, never by installation id alone — see {@link #installation}. */
    private final Map<String, CachedInstallation> installationCache = new ConcurrentHashMap<>();

    @Autowired
    public GitHubAppService(
            ObjectMapper objectMapper, @Value("${github.api.url:https://api.github.com}") String githubApiUrl) {
        this(
                objectMapper,
                githubApiUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Clock.systemUTC());
    }

    GitHubAppService(ObjectMapper objectMapper, String githubApiUrl, HttpClient httpClient, Clock clock) {
        this.objectMapper = objectMapper;
        this.githubApiUrl = githubApiUrl;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    /** An installation's account and the permissions GitHub granted it, as read by {@link #installation}. */
    record Installation(String account, Map<String, String> permissions) {}

    private record CachedInstallation(Installation installation, Instant expiresAt) {}

    /**
     * Looks up the installation (account login, granted permissions), caching the answer for
     * {@link #INSTALLATION_TTL} keyed by the credential fingerprint — never by installation id
     * alone, so two different credentials that happen to name the same installation id (one per
     * organization, in a deployment that resolves credentials per organization) never answer one
     * organization's lookup from another's cache entry.
     */
    Installation installation(String appId, String installationId, String privateKeyPem, String jwt) {
        String key = fingerprint(appId, installationId, privateKeyPem);
        Instant now = clock.instant();
        CachedInstallation cached = installationCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.installation();
        }
        Installation installation = fetchInstallation(installationId, jwt);
        installationCache.put(key, new CachedInstallation(installation, now.plus(INSTALLATION_TTL)));
        return installation;
    }

    private void invalidateInstallation(String appId, String installationId, String privateKeyPem) {
        installationCache.remove(fingerprint(appId, installationId, privateKeyPem));
    }

    private Installation fetchInstallation(String installationId, String jwt) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/app/installations/" + installationId))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // Same reasoning as the mint's own non-201 branch: the body is never echoed into
                // the exception, since GitHub can mirror the request (Authorization header
                // included) into an error payload.
                throw new GitHubTokenMintException(
                        response.statusCode(), installationId, rateLimitHints(response.headers()));
            }
            JsonNode body = objectMapper.readTree(response.body());
            String account = body.path("account").path("login").asText(null);
            if (account == null || account.isBlank()) {
                throw new IllegalStateException(
                        "GitHub installation lookup for " + installationId + " returned no account login");
            }
            Map<String, String> permissions = new TreeMap<>();
            body.path("permissions")
                    .fields()
                    .forEachRemaining(entry ->
                            permissions.put(entry.getKey(), entry.getValue().asText()));
            return new Installation(account, permissions);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to look up GitHub installation " + installationId, e);
        }
    }

    /**
     * SHA-256 over {@code appId}, {@code installationId} and the PEM bytes, each followed by a zero
     * byte — the installation cache's key. The PEM must stay in it: without the key, two
     * organizations naming the same installation id would share one cache entry.
     */
    private static String fingerprint(String appId, String installationId, String privateKeyPem) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(appId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(installationId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(privateKeyPem.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JDK provider; this can only mean a broken JVM install.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Mints a token narrowed to {@code scope}: an empty {@link GitHubTokenScope#repositories()} is
     * refused before any call, then the installation's account and grants narrow the request (see
     * {@link GitHubTokenScope#narrowTo}) before the actual mint.
     *
     * @throws GitHubTokenScopeException if the scope is empty, narrows to nothing, or GitHub
     *     answers the narrowed mint with 422 — in the last case the cached installation lookup is
     *     dropped so the next attempt re-reads it, and the message names the requested {@code
     *     owner/name} repositories and the account
     */
    public String generateInstallationToken(
            String appId, String installationId, String privateKeyPem, GitHubTokenScope scope) {
        if (scope.repositories().isEmpty()) {
            throw new GitHubTokenScopeException(
                    "No repository in this run's scope; refusing to mint an installation-wide token");
        }
        try {
            String jwt = createSignedJwt(appId, privateKeyPem);
            Installation installation = installation(appId, installationId, privateKeyPem, jwt);
            GitHubTokenScope.Request narrowed = scope.narrowTo(installation.account(), installation.permissions());

            HttpResponse<String> response = postAccessToken(
                    installationId, jwt, new MintBody(narrowed.repositoryNames(), narrowed.permissions()));
            if (response.statusCode() == 422) {
                invalidateInstallation(appId, installationId, privateKeyPem);
                List<String> requested = narrowed.repositoryNames().stream()
                        .map(name -> installation.account() + "/" + name)
                        .sorted()
                        .toList();
                throw new GitHubTokenScopeException(
                        "GitHub rejected minting a token for " + requested + " under installation account '"
                                + installation.account()
                                + "'; each repository must be in the App installation's repository selection"
                                + " and every requested permission granted",
                        new GitHubTokenMintException(422, installationId, rateLimitHints(response.headers())));
            }
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

    /**
     * Mints a metadata-only token and discards it — used by a caller that needs to prove an App
     * credential can mint, without a run's scope to narrow by. Same status-based exceptions as
     * {@link #generateInstallationToken}, so a caller classifying 401/403 keeps working.
     */
    public void verifyAppCredential(String appId, String installationId, String privateKeyPem) {
        try {
            String jwt = createSignedJwt(appId, privateKeyPem);
            HttpResponse<String> response =
                    postAccessToken(installationId, jwt, Map.of("permissions", Map.of("metadata", "read")));
            if (response.statusCode() != 201) {
                throw new GitHubTokenMintException(
                        response.statusCode(), installationId, rateLimitHints(response.headers()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while verifying GitHub App credential", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify GitHub App credential", e);
        }
    }

    /** A narrowed mint's body; a record so the JSON keeps one field order across JVM runs. */
    private record MintBody(List<String> repositories, Map<String, String> permissions) {}

    private HttpResponse<String> postAccessToken(String installationId, String jwt, Object body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/app/installations/" + installationId + "/access_tokens"))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
     * A pull request's full state for the merge path — enough to classify a row before merging.
     * Unlike {@link PullRequestSnapshot}, which the reconciler reads on every tick, this carries the
     * fields only {@code PullRequestMergeService} needs: a draft flag, mergeability, and the
     * head/base refs a merge call pins to.
     */
    public record PullRequestDetail(
            String state,
            boolean merged,
            Instant mergedAt,
            boolean draft,
            Boolean mergeable,
            String headSha,
            String baseRef) {}

    /**
     * Reads a pull request's full detail for the merge path. Same idiom as {@link
     * #fetchPullRequest}: a non-200 status throws {@link GitHubApiException} with the status as a
     * field, and an I/O or interrupt failure wraps in {@link RuntimeException}. The response body is
     * never kept beyond the fields parsed here.
     */
    public PullRequestDetail fetchPullRequestDetail(String token, String ownerRepo, int prNumber) {
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
            return parsePullRequestDetail(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to read " + ownerRepo + "#" + prNumber + ": " + e.getMessage(), e);
        }
    }

    /**
     * Merges a pull request — {@code PUT /repos/{ownerRepo}/pulls/{n}/merge} — pinned to the head
     * SHA seen during inspection (omitted from the body when null) so a commit pushed after
     * inspection but before this call refuses rather than silently merging something nobody read.
     *
     * @return the merge commit SHA GitHub returns
     * @throws GitHubMergeRefusedException on 405, 409 or 422 — GitHub refused to merge, carrying its
     *     own reason (the {@code message} field only, capped at 300 characters and with the token
     *     scrubbed)
     * @throws GitHubApiException on any other non-2xx status
     * @throws RuntimeException if the call never produced a status at all
     */
    public String mergePullRequest(
            String token, String ownerRepo, int prNumber, String mergeMethod, String expectedHeadSha) {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("merge_method", mergeMethod);
        if (expectedHeadSha != null) {
            bodyMap.put("sha", expectedHeadSha);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(bodyMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize GitHub merge payload for " + ownerRepo + "#" + prNumber, e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/repos/" + ownerRepo + "/pulls/" + prNumber + "/merge"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(30))
                .method("PUT", HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                try {
                    JsonNode responseBody = objectMapper.readTree(response.body());
                    return responseBody.path("sha").asText(null);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to parse GitHub merge response for " + ownerRepo + "#" + prNumber, e);
                }
            }
            if (status == 405 || status == 409 || status == 422) {
                throw new GitHubMergeRefusedException(
                        status,
                        ownerRepo,
                        prNumber,
                        extractMergeRefusalReason(response.body(), status, token),
                        rateLimitHints(response.headers()));
            }
            throw new GitHubApiException(status, ownerRepo, prNumber, rateLimitHints(response.headers()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to merge " + ownerRepo + "#" + prNumber + ": " + e.getMessage(), e);
        }
    }

    /**
     * GitHub's {@code message} field only, capped and with the token scrubbed — everything else in
     * a merge-refusal body is deliberately discarded, for the same reason {@link #fetchPullRequest}
     * never keeps a response body: GitHub can echo the request, {@code Authorization} header
     * included, and this text reaches a 409 response and the audit log.
     */
    private String extractMergeRefusalReason(String body, int status, String token) {
        String message = null;
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode messageNode = node.get("message");
            if (messageNode != null && !messageNode.isNull()) {
                message = messageNode.asText();
            }
        } catch (Exception e) {
            // Falls through to the fixed fallback below — the body wasn't parsable JSON.
        }
        if (message == null || message.isBlank()) {
            return "GitHub refused the merge (HTTP " + status + ")";
        }
        // Scrub before capping: a cap that cuts through the token leaves a prefix no replace matches.
        if (token != null && !token.isBlank()) {
            message = message.replace(token, "***");
        }
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        return message;
    }

    /** A newly-filed issue's identity, as returned by {@link #createIssue}. */
    public record CreatedIssue(int number, String htmlUrl) {}

    /**
     * Files a new issue — used by roadmap-extension materialization to give a newly-created Task a
     * matching GitHub issue. Same idiom as {@link #fetchPullRequest}: a non-2xx status throws
     * {@link GitHubApiException}, an I/O or interrupt failure wraps in {@link RuntimeException}.
     */
    public CreatedIssue createIssue(String token, String ownerRepo, String title, String body) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("title", title, "body", body != null ? body : ""));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize GitHub issue payload for " + ownerRepo, e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiUrl + "/repos/" + ownerRepo + "/issues"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GitHubApiException(response.statusCode(), ownerRepo, rateLimitHints(response.headers()));
            }
            JsonNode body2 = objectMapper.readTree(response.body());
            return new CreatedIssue(
                    body2.get("number").asInt(), body2.get("html_url").asText());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to create issue for " + ownerRepo + ": " + e.getMessage(), e);
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
     *     GitHubApiException#getStatus()}
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
     * <p>Legacy: its only caller is {@code BranchCleanupService#cleanupBranches}, which itself
     * serves only workflow histories recorded before the {@code branch-cleanup-on-worker} version
     * marker. The Worker's own GitHub client deletes a ref for every run that reaches cleanup
     * after that marker.
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

    PullRequestDetail parsePullRequestDetail(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode mergedAtNode = node.get("merged_at");
            JsonNode mergeableNode = node.get("mergeable");
            JsonNode headNode = node.get("head");
            JsonNode baseNode = node.get("base");
            return new PullRequestDetail(
                    node.path("state").asText(null),
                    node.path("merged").asBoolean(false),
                    mergedAtNode == null || mergedAtNode.isNull() ? null : Instant.parse(mergedAtNode.asText()),
                    node.path("draft").asBoolean(false),
                    mergeableNode == null || mergeableNode.isNull() ? null : mergeableNode.asBoolean(),
                    headNode == null ? null : headNode.path("sha").asText(null),
                    baseNode == null ? null : baseNode.path("ref").asText(null));
        } catch (Exception e) {
            // Deliberately does not interpolate the exception's message: see parsePullRequest.
            throw new RuntimeException("Failed to parse GitHub pull request detail payload", e);
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

package com.choruskube.core.controller;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.service.SingleFleetWorkerAuthorizer;
import com.choruskube.core.service.WorkerAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands a Worker that holds no credential the run's GitHub token; only for runs the Worker's
 * credential may act on.
 *
 * <p>Multi-tenant only: a single-tenant server holds no credential, so it does not serve this route.
 */
@RestController
@RequestMapping("/worker/runs/{runId}")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class WorkerGitHubTokenController {

    private static final Logger log = LoggerFactory.getLogger(WorkerGitHubTokenController.class);

    private final WorkerAuthorizer authorizer;
    private final GitHubCredentialResolver resolver;

    public WorkerGitHubTokenController(
            ObjectProvider<WorkerAuthorizer> authorizerProvider,
            @Value("${worker.registration.token:}") String registrationToken,
            GitHubCredentialResolver resolver) {
        this.authorizer = authorizerProvider.getIfAvailable(() -> new SingleFleetWorkerAuthorizer(registrationToken));
        this.resolver = resolver;
    }

    @GetMapping("/github-token")
    public ResponseEntity<Map<String, String>> token(HttpServletRequest httpRequest, @PathVariable UUID runId) {
        String credential = WorkerCredentialController.credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        try {
            return ResponseEntity.ok(Map.of("token", resolver.getTokenForRun(runId)));
        } catch (RuntimeException e) {
            // The message only; a cause chain from the GitHub client could carry response detail.
            log.warn("Could not resolve a GitHub token for run {}: {}", runId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "No usable GitHub credential for this run"));
        }
    }
}

package com.choruskube.core.config.e2e;

import com.choruskube.core.service.ArtifactRetentionService;
import com.choruskube.core.service.AuthorizationService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an end-to-end spec expire one run it owns through a real retention pass, so the suite can
 * show an expired run without a short period that would expire every other spec's runs mid-suite.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/v1/e2e/runs")
public class E2eArtifactRetentionController {

    private final ArtifactRetentionService retention;
    private final AuthorizationService authService;

    public E2eArtifactRetentionController(ArtifactRetentionService retention, AuthorizationService authService) {
        this.retention = retention;
        this.authService = authService;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/expire-artifacts")
    public ResponseEntity<Void> expireArtifacts(@PathVariable UUID runId) {
        authService.checkOrgAccess("workflow_run", runId);
        retention.expireNow(runId);
        return ResponseEntity.noContent().build();
    }
}

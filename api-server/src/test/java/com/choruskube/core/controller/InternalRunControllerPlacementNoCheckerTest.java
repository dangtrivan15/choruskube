package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.service.NodePlacementChecker;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test (no Spring context) for the {@link NodePlacementChecker} seam's absent-implementation
 * default: a genuinely empty {@code Optional}, as Spring injects when no bean of that type exists
 * — the case every OSS single-worker deployment runs. {@link InternalRunControllerPlacementTest}
 * cannot exercise this: its {@code @MockitoBean} makes the {@code Optional} non-empty for every
 * test in that class.
 */
class InternalRunControllerPlacementNoCheckerTest {

    @Test
    void placementCheck_noChecker_allowsWithEmptyReason() {
        InternalRunController controller = new InternalRunController(
                null, // service
                null, // gitHubCredentialResolver
                null, // runPullRequestService
                null, // artifactResolutionService
                null, // branchCleanupService
                Optional.empty()); // placementChecker

        NodePlacementChecker.PlacementDecision decision =
                controller.placementCheck(UUID.randomUUID(), UUID.randomUUID());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEmpty();
    }
}

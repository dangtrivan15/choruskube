package com.choruskube.core.dto;

import com.choruskube.core.service.GateDriftCheckService;
import java.util.List;

/** {@code held_for_recheck}: main moved under the run, so the approval waits for a re-check. */
public record SignalResponse(String outcome, List<StaleRepo> staleRepos) {

    public static final String SIGNALED = "signaled";
    public static final String HELD_FOR_RECHECK = "held_for_recheck";

    public record StaleRepo(String repo, String detail) {}

    public static SignalResponse signaled() {
        return new SignalResponse(SIGNALED, List.of());
    }

    public static SignalResponse held(GateDriftCheckService.DriftCheck drift) {
        return new SignalResponse(
                HELD_FOR_RECHECK,
                drift.staleRepos().stream()
                        .map(r -> new StaleRepo(r.ownerRepo(), r.summary()))
                        .toList());
    }
}

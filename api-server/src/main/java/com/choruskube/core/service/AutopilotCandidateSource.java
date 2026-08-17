package com.choruskube.core.service;

import java.util.List;
import java.util.UUID;

/**
 * Which Epics an Autopilot may draw work from. Core is single-tenant and sees everything; a
 * downstream overlay resolves the Autopilot's org from its id and filters.
 *
 * <p>A seam rather than a direct query because the tick runs on a timer thread with no request
 * scope — the org must be derived from an id the caller already holds, which is the dominant
 * pattern for background callers in this codebase.
 */
public interface AutopilotCandidateSource {

    /**
     * @param autopilotId the Autopilot asking; the only thing a background caller has to derive
     *     scope from. Implementations must not read request-scoped tenant state.
     */
    List<UUID> candidateEpicIds(UUID autopilotId);
}

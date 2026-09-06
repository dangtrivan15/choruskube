package com.choruskube.core.service;

import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.Collection;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-tenant default (gated the same way as {@code SingleTenantAutopilotResolver}): occupancy is
 * the runs this Autopilot itself started. With one Autopilot and no per-run ownership to scope by,
 * this is the definition the installation can express — and the only one the shared, never-reset
 * integration database can exercise deterministically, since counting every run row would fold in
 * whatever other non-transactional tests have committed.
 *
 * <p>It therefore does <strong>not</strong> count a person's manually started run (its {@code
 * autopilot_id} is null). That undercount is a narrow, same-operator concern here; the case that
 * matters — a member of a shared organisation launching work the Autopilot must budget against — is
 * multi-tenant, where run-ownership records give a clean scope and the org-scoped overlay counts
 * those pods. Widening occupancy is exactly what this seam exists to let that overlay do.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantAutopilotSlotCounter implements AutopilotSlotCounter {

    private final WorkflowRunRepository runRepo;

    public SingleTenantAutopilotSlotCounter(WorkflowRunRepository runRepo) {
        this.runRepo = runRepo;
    }

    @Override
    public long occupiedSlots(UUID autopilotId, Collection<WorkflowRunStatus> occupyingStatuses) {
        return runRepo.countByAutopilotIdAndStatusIn(autopilotId, occupyingStatuses);
    }
}

package com.choruskube.core.service;

import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.ArtifactUploadBookingRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/**
 * The reservation ledger behind the run-upload budget: an upload is admitted, and its bytes
 * booked, before any byte moves — never after the body has already landed in the store.
 */
@Service
public class ArtifactUploadBudget {

    private final WorkflowRunRepository runRepo;
    private final ArtifactUploadBookingRepository bookingRepo;
    private final long budgetBytes;

    public ArtifactUploadBudget(
            WorkflowRunRepository runRepo,
            ArtifactUploadBookingRepository bookingRepo,
            @Value("${objectstore.run-upload-budget}") DataSize budget) {
        this.runRepo = runRepo;
        this.bookingRepo = bookingRepo;
        this.budgetBytes = budget.toBytes();
    }

    /**
     * Locks the run row for the rest of this transaction, so two concurrent admissions for the
     * same run never both compute the budget check against the same pre-booking total. A refusal
     * writes nothing, leaving the run's booked total exactly as it was.
     */
    @Transactional
    public Admission admit(UUID runId, String objectKey, long declaredBytes) {
        WorkflowRun run = runRepo.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException("Run not found for upload admission: " + runId));

        if (WorkflowRunStatusGroups.TERMINAL.contains(run.getStatus())) {
            return new Admission.RunClosed(run.getStatus());
        }

        long existingForKey = bookingRepo.findBookedBytes(runId, objectKey).orElse(0L);
        long othersBooked = bookingRepo.sumBookedBytesExcludingKey(runId, objectKey);
        long newBookingForKey = Math.max(existingForKey, declaredBytes);

        // Compared as headroom, never as a sum: a declaration near Long.MAX_VALUE added to the run's
        // other bookings wraps negative and would pass the check, booking an unbounded size.
        if (newBookingForKey > budgetBytes - othersBooked) {
            return new Admission.OverBudget(budgetBytes, othersBooked + existingForKey, declaredBytes);
        }

        bookingRepo.upsertKeepingLargest(runId, objectKey, declaredBytes);
        return new Admission.Admitted();
    }

    /** The outcome of an admission check: exactly one of these, never a partial booking. */
    public sealed interface Admission {
        record Admitted() implements Admission {}

        record RunClosed(WorkflowRunStatus status) implements Admission {}

        record OverBudget(long budgetBytes, long bookedBytes, long declaredBytes) implements Admission {}
    }
}

package com.choruskube.core.repository;

import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RunPullRequestRepository extends JpaRepository<RunPullRequest, UUID> {

    List<RunPullRequest> findByWorkflowRunId(UUID workflowRunId);

    Optional<RunPullRequest> findByWorkflowRunIdAndPrUrl(UUID workflowRunId, String prUrl);

    /**
     * Unmerged PRs that are due for a check, longest-overdue first, for the state reconciler's
     * batch. Uses {@link Pageable} for the limit rather than the native {@code LIMIT} that
     * {@code findTombstonedBatch} uses — this query needs no native features.
     *
     * <p>Three parts of this are load-bearing, and each replaces a way the old scan could stall.
     *
     * <p><strong>{@code nextCheckAt}, not {@code stateCheckedAt}.</strong> The old scan sorted by
     * when GitHub last answered, and nothing wrote that on a failure — so a row that always fails
     * was never stamped and kept its place at the front of an order always read from page zero.
     * Enough such rows and the scan never reaches a healthy PR again, so merges are never learned
     * and Tasks never close. Sorting by when a row is next due lets a failure yield its place
     * without claiming to have been checked.
     *
     * <p><strong>{@code prNumber IS NOT NULL}.</strong> Not a candidate at all rather than a
     * candidate that fails: no amount of retrying makes such a row queryable, and it used to be
     * selected and then returned from without an exception — so it took a batch slot forever and
     * said nothing above debug. The Autopilot panel surfaces these instead.
     *
     * <p><strong>{@code p.id} as a second sort key.</strong> Equal due times are the common case,
     * not an edge case — every row V17 backfilled without a {@code state_checked_at} shares one, as
     * does every row that failed in the same tick. Without it the order among them is undefined,
     * so a row can be skipped indefinitely by nothing worse than a change of plan.
     *
     * @param now the tick's clock reading; a row is due when {@code nextCheckAt} has passed it
     */
    @Query("""
            SELECT p FROM RunPullRequest p
            WHERE p.mergedAt IS NULL AND p.prNumber IS NOT NULL AND p.nextCheckAt <= :now
            ORDER BY p.nextCheckAt ASC, p.id ASC
            """)
    List<RunPullRequest> findUnmergedBatch(Instant now, Pageable pageable);

    /**
     * Unmerged PRs on this Autopilot's own runs that carry no PR number, and so can never be
     * resolved from GitHub. Counted rather than listed: the panel needs to say that some work can
     * never complete, not which rows.
     *
     * <p>Scoped through {@code workflow_run.autopilot_id} because the tick runs on a timer thread
     * with no request scope — the same reason every other Autopilot query derives scope from an id
     * the caller already holds.
     */
    @Query("""
            SELECT COUNT(p) FROM RunPullRequest p, WorkflowRun r
            WHERE p.workflowRunId = r.id AND r.autopilotId = :autopilotId
              AND p.mergedAt IS NULL AND p.prNumber IS NULL
            """)
    long countUnresolvableForAutopilot(UUID autopilotId);

    /**
     * Open Tasks this Autopilot started whose pull requests GitHub can no longer be asked about —
     * quarantined by {@code PullRequestStateService} rather than allowed to stop the Autopilot.
     *
     * <p>Counted by <em>Task</em>, not by row: one Task with four unreadable pull requests is one
     * thing for a human to fix, and reporting it as four reads like four separate problems.
     *
     * <p><strong>{@code status <> done} is the point of the query.</strong> A quarantined pull
     * request on a closed Task blocks nothing — its Task already reached the state the merge would
     * have produced — and reporting it would put permanent noise in a panel whose whole job is to
     * say why the Autopilot is idle right now.
     *
     * <p><strong>Scoped through {@code workflow_run.autopilot_id}</strong>, matching {@link
     * #countUnresolvableForAutopilot} and every other background-caller query here. That leaves one
     * blind spot worth naming: a Task whose run a <em>human</em> started carries no {@code
     * autopilot_id}, so if its pull request becomes unreadable it will block dependents in the
     * frontier without appearing in this count. Closing that gap means scoping by the Autopilot's
     * candidate Epics instead — the same set {@code computeFrontier} walks — which is a wider
     * change than this reason line justifies on its own.
     *
     * <p>{@code done} is a bind parameter rather than a JPQL enum literal because {@code
     * Task.status} maps to a Postgres named enum ({@code work_item_status}). Hibernate renders an
     * inline literal with a cast built from the <em>Java</em> type name — {@code
     * 'done'::WorkItemStatus} — which no database has ever had a type for, and the failure is a
     * runtime SQL error rather than anything the compiler or a JPQL parse would catch.
     */
    @Query("""
            SELECT COUNT(DISTINCT t.id) FROM RunPullRequest p, WorkflowRun r, Task t
            WHERE p.workflowRunId = r.id AND r.taskId = t.id
              AND r.autopilotId = :autopilotId
              AND p.unreadableSince IS NOT NULL
              AND t.status <> :done
            """)
    long countTasksBlockedByUnreadablePullRequests(UUID autopilotId, WorkItemStatus done);
}

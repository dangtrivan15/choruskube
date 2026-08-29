-- At most one unfinished run per Task.
--
-- Application code already serialises starts on the Task row (DefaultTaskService#startCore takes
-- a pessimistic lock, refreshes, and rejects a start while the most recent run is non-terminal),
-- and no Task has ever held two. This makes the rule a fact of the schema rather than a property
-- of one code path, so a future caller that starts a run without taking that lock fails loudly
-- instead of quietly giving one Task two agents on the same repository.
--
-- Partial, because the rule is only about unfinished runs: a Task accumulates finished ones for
-- tracing and must be free to hold as many as it likes. The status list is the complement of
-- WorkflowRunStatusGroups.ACTIVE -- keep the two in step when adding a workflow_run_status value.
CREATE UNIQUE INDEX uq_workflow_run_one_live_per_task
    ON workflow_run (task_id)
    WHERE task_id IS NOT NULL
      AND deleted_at IS NULL
      AND status NOT IN ('completed', 'failed', 'cancelled');

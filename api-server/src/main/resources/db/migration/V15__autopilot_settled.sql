-- The Autopilot's failure breaker settles each of its runs exactly once.
--
-- The obvious implementation — "runs that finished since last_tick_at" — is
-- wrong, because awaiting_retry is a durable STATUS rather than an event. Any
-- unrelated re-save of a dead run (the pull-request reconciler, a node-execution
-- update) puts it back inside the window, and the same run is counted as a fresh
-- failure on every tick that follows. Three touches of one dead run would trip a
-- three-strike breaker on its own.
--
-- An explicit marker makes settling idempotent by construction: a run is
-- classified when it is first seen in a terminal-or-awaiting_retry state, stamped,
-- and never looked at again.
ALTER TABLE public.workflow_run
    ADD COLUMN autopilot_settled_at TIMESTAMPTZ;

-- The tick's settle query in full: unsettled runs of one Autopilot. Partial, so
-- it stays small — a run leaves the index for good the moment it is stamped.
CREATE INDEX idx_workflow_run_autopilot_unsettled
    ON public.workflow_run (autopilot_id)
    WHERE autopilot_id IS NOT NULL AND autopilot_settled_at IS NULL;

-- Backoff for the PR-state scan.
--
-- V13's scan ordered by `state_checked_at ASC NULLS FIRST` and nothing wrote
-- that column on a failure, deliberately: a transient failure then cost one
-- interval and nothing else. It has no bound for a permanent one. A row that
-- always fails is never stamped, so it keeps its place at the front of an
-- order that is always read from page zero — and once `batch_size` rows are
-- in that state the reconciler stops examining healthy rows entirely, so
-- merges are never learned and Tasks never close.
--
-- `next_check_at` replaces `state_checked_at` as the sort key so that a
-- failure can defer a row without pretending it was checked. `state_checked_at`
-- keeps its old meaning — when GitHub last answered — and is still written on
-- success only.
ALTER TABLE public.run_pull_request
    ADD COLUMN failure_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_check_at timestamp with time zone NOT NULL DEFAULT now();

-- Carry the existing least-recently-checked order across rather than making
-- every row equally due: `created_at` is NOT NULL, so this leaves no nulls
-- behind and the never-checked rows still sort ahead of the checked ones.
UPDATE public.run_pull_request
    SET next_check_at = COALESCE(state_checked_at, created_at);

-- The sort key changed, so the old index can no longer serve the scan.
DROP INDEX IF EXISTS public.idx_run_pull_request_unmerged;

-- `pr_number IS NOT NULL` is in the predicate, not just the query: a row with
-- no PR number cannot be queried from GitHub by any amount of retrying, so it
-- is not a candidate at all. Keeping it out of the index keeps it out of the
-- scan for free. Such rows are surfaced on the Autopilot panel instead, since
-- the Task behind one can never close.
--
-- `id` is a real part of the key, not decoration. Without it the order is
-- undefined for equal timestamps, which is the common case immediately after
-- a backfill or when several rows fail in the same tick.
CREATE INDEX idx_run_pull_request_due
    ON public.run_pull_request (next_check_at, id)
    WHERE merged_at IS NULL AND pr_number IS NOT NULL;

-- PR state for the "done means merged" invariant (Decision 8).
--
-- All three columns are nullable and default NULL: every pre-existing row is
-- "never checked", which the reconciler picks up on its next tick. `merged_at
-- IS NULL` is the single authority for "not merged" — a PR can be `closed`
-- without ever being merged, so `state` alone cannot answer the guard.
ALTER TABLE public.run_pull_request
    ADD COLUMN state text,
    ADD COLUMN merged_at timestamp with time zone,
    ADD COLUMN state_checked_at timestamp with time zone;

-- The reconciler scans only unmerged rows, oldest-checked first. A partial
-- index keeps that scan off the merged rows, which accumulate forever.
CREATE INDEX idx_run_pull_request_unmerged
    ON public.run_pull_request (state_checked_at)
    WHERE merged_at IS NULL;

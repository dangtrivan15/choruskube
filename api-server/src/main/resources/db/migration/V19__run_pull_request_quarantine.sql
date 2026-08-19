-- A pull request whose merge state can never be read again, and why.
--
-- Nullable and cleared on every successful refresh: quarantine is a live condition, not an
-- event log. A row that starts answering again must leave quarantine on its own, or a
-- transferred repository would stay flagged until somebody noticed.
--
-- The reason is stored rather than recomputed because the Autopilot panel renders it verbatim
-- and the reconciler is the only thing that ever saw the failure.
ALTER TABLE run_pull_request
    ADD COLUMN unreadable_since  timestamptz,
    ADD COLUMN unreadable_reason text;

-- The panel asks "which of this Autopilot's open Tasks are stuck", never "list every stuck row",
-- so the index carries the predicate rather than the whole column.
CREATE INDEX run_pull_request_unreadable_idx
    ON run_pull_request (workflow_run_id)
    WHERE unreadable_since IS NOT NULL;

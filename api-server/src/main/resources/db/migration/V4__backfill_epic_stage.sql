-- Backfill the persisted Epic board stage for Epics that predate V3.
--
-- Why this is needed: V2 wrapped every legacy `feature_proposal` as Epic -> Story -> Task, but
-- `work_item_status` had no `rolled_out` member at that point, so V2's CASE collapsed rolled-out
-- proposals into `task.status = 'done'` — losing the distinction at the Epic level. V3 then added
-- `epic.stage` with a blanket `DEFAULT 'backlog'` (a default is not a backfill) and re-added
-- `rolled_out` to the enum, but never recovered what V2 had flattened. Result: every pre-existing
-- Epic sits in the board's Backlog column, including ones that shipped long ago.
--
-- The legacy `feature_proposal` rows were deliberately retained by V2 (see its trailing NOTE) and
-- V2 preserved `epic.id = feature_proposal.id`, so the original roadmap status is still joinable —
-- this restores the real board position rather than inferring one from task counts.
--
-- Must be its own migration, not an edit to V3: Postgres forbids using an enum value in the same
-- transaction as the `ALTER TYPE ... ADD VALUE` that created it.

WITH epic_task_state AS (
    SELECT s.epic_id,
           bool_or(t.status <> 'backlog') AS has_started_task
    FROM public.story s
    JOIN public.task t ON t.story_id = s.id
    GROUP BY s.epic_id
)
UPDATE public.epic e
SET stage = CASE
        -- A legacy 'rolled_out' is an authoritative shipment record: it wins outright.
        WHEN fp.status = 'rolled_out' THEN 'rolled_out'::public.work_item_status
        -- The legacy status is frozen as of V2; live Task state is not. If work has started (or
        -- finished) since that snapshot, the Epic is at least in progress. Deliberately NOT
        -- 'rolled_out' even when every Task is done: "done" means code-complete, not shipped, and
        -- stage is human-owned — the final move to Rolled Out stays a deliberate act on the board.
        WHEN COALESCE(ts.has_started_task, FALSE) THEN 'in_progress'::public.work_item_status
        -- Otherwise the legacy status still describes the Epic; 'rolled_out' is already handled
        -- above, so the remaining members map across by name.
        ELSE fp.status::text::public.work_item_status
    END
FROM public.feature_proposal fp
LEFT JOIN epic_task_state ts ON ts.epic_id = fp.id
WHERE fp.id = e.id
  -- Only repair Epics still at V3's blanket default. A stage a human already dragged on the board
  -- outranks the legacy snapshot, and this keeps the migration safe to reason about if replayed.
  AND e.stage = 'backlog';

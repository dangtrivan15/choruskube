-- Backfill: a Story with at least one descendant Task that has left `backlog` is at least
-- in progress. Unlike Epic's V4 backfill, there is no legacy status source to recover here
-- (Story was created fresh by V2, never carried a feature_proposal-style prior status), so
-- this heuristic is the full backfill, not a supplement to a recovered legacy value.
WITH story_task_state AS (
    SELECT story_id, bool_or(status <> 'backlog') AS has_started_task
    FROM public.task
    GROUP BY story_id
)
UPDATE public.story s
SET stage = 'in_progress'
FROM story_task_state ts
WHERE ts.story_id = s.id
  AND ts.has_started_task
  AND s.stage = 'backlog';

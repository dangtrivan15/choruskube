-- The Autopilot: a standing controller, one per installation.
--
-- Deliberately NOT seeded. No row means "never configured": the read path
-- returns a synthetic disengaged status and the row is INSERTed on first
-- mutation. That keeps GET read-only and avoids a seeder, which matters
-- because ownership publication reads request-scoped state that a
-- background thread does not have.
CREATE TABLE public.autopilot (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    engaged              BOOLEAN     NOT NULL DEFAULT false,
    max_parallel         INT         NOT NULL DEFAULT 1 CHECK (max_parallel >= 1),
    consecutive_failures INT         NOT NULL DEFAULT 0,
    disengaged_reason    TEXT,
    last_tick_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Attribution, not ownership: a run outlives the controller that started it.
-- ON DELETE SET NULL because workflow_run carries external side effects and
-- must never be cascade-deleted by the removal of a control-plane row —
-- losing attribution is the correct degradation.
ALTER TABLE public.workflow_run
    ADD COLUMN autopilot_id UUID REFERENCES public.autopilot(id) ON DELETE SET NULL;

CREATE INDEX idx_workflow_run_autopilot_id
    ON public.workflow_run (autopilot_id) WHERE autopilot_id IS NOT NULL;

-- Postgres 12+ allows ADD VALUE in Flyway's transaction; the value is not used by this migration.
ALTER TYPE node_execution_status ADD VALUE IF NOT EXISTS 'finalizing';

-- 'async': the gate was opened by workflow code that finalizes approvals itself; NULL: the
-- synchronous protocol of gates opened before it, whose workflow waits for a final decision.
ALTER TABLE public.node_execution ADD COLUMN finalize_mode TEXT;

ALTER TABLE public.gate_stamp ADD COLUMN materialized_at TIMESTAMPTZ;
ALTER TABLE public.gate_stamp ADD COLUMN materialization_note TEXT;
ALTER TABLE public.gate_stamp DROP CONSTRAINT IF EXISTS gate_stamp_status_check;
ALTER TABLE public.gate_stamp ADD CONSTRAINT gate_stamp_status_check
    CHECK (status IN ('pending', 'consumed', 'voided', 'finalizing'));

-- One live approval per gate, whether it is being finalized or held for a re-check.
DROP INDEX IF EXISTS public.uq_gate_stamp_pending;
CREATE UNIQUE INDEX uq_gate_stamp_live ON public.gate_stamp (workflow_run_id, gate_template_node_id)
    WHERE status IN ('pending', 'finalizing');

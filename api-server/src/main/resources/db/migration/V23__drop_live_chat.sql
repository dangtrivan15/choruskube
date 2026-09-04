-- LiveChat has been removed. Reclassify any surviving rows, drop the
-- tables and the orphaned live_chat_status type, then recreate the two
-- status enum types without the live_chat value.

UPDATE node_execution SET status = 'skipped'   WHERE status = 'live_chat';
UPDATE workflow_run   SET status = 'cancelled' WHERE status = 'live_chat';

DROP TABLE IF EXISTS public.live_chat_message;
DROP TABLE IF EXISTS public.live_chat_session;
DROP TYPE  IF EXISTS public.live_chat_status;

-- node_execution_status: remove live_chat
-- Drop indexes on the status column, go through text to sidestep
-- constraint/operator mismatches, then recreate indexes.
DROP INDEX IF EXISTS idx_node_execution_status;
DROP INDEX IF EXISTS idx_node_execution_status_started;
DROP INDEX IF EXISTS idx_node_execution_status_label;
ALTER TABLE node_execution ALTER COLUMN status DROP DEFAULT;
ALTER TABLE node_execution ALTER COLUMN status TYPE text;
DROP TYPE node_execution_status;
CREATE TYPE node_execution_status AS ENUM (
    'pending', 'running', 'paused', 'awaiting_human',
    'completed', 'failed', 'skipped', 'invalidated'
);
ALTER TABLE node_execution
    ALTER COLUMN status TYPE node_execution_status
    USING status::node_execution_status;
ALTER TABLE node_execution ALTER COLUMN status SET DEFAULT 'pending';
CREATE INDEX idx_node_execution_status ON node_execution USING btree (status);
CREATE INDEX idx_node_execution_status_started ON node_execution USING btree (status, started_at);
CREATE INDEX idx_node_execution_status_label ON node_execution USING btree (status, label) WHERE (label IS NOT NULL);

-- workflow_run_status: remove live_chat
DROP INDEX IF EXISTS idx_workflow_run_status;
DROP INDEX IF EXISTS idx_workflow_run_status_created;
DROP INDEX IF EXISTS uq_workflow_run_one_live_per_task;
ALTER TABLE workflow_run ALTER COLUMN status DROP DEFAULT;
ALTER TABLE workflow_run ALTER COLUMN status TYPE text;
DROP TYPE workflow_run_status;
CREATE TYPE workflow_run_status AS ENUM (
    'pending', 'running', 'paused', 'completed',
    'failed', 'cancelled', 'awaiting_human', 'awaiting_retry'
);
ALTER TABLE workflow_run
    ALTER COLUMN status TYPE workflow_run_status
    USING status::workflow_run_status;
ALTER TABLE workflow_run ALTER COLUMN status SET DEFAULT 'pending';
CREATE INDEX idx_workflow_run_status ON workflow_run USING btree (status);
CREATE INDEX idx_workflow_run_status_created ON workflow_run USING btree (status, created_at DESC);
CREATE UNIQUE INDEX uq_workflow_run_one_live_per_task
    ON workflow_run (task_id)
    WHERE task_id IS NOT NULL
      AND deleted_at IS NULL
      AND status NOT IN ('completed', 'failed', 'cancelled');

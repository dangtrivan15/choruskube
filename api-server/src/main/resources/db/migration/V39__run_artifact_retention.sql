-- When retention deleted a closed run's storage tree; null while the run's artifacts remain.
ALTER TABLE workflow_run ADD COLUMN artifacts_expired_at TIMESTAMPTZ;
-- Retention counts from completed_at, so a closed run without one would never expire. Stamping the
-- migration's own time postpones such a run's deletion and never hastens it.
UPDATE workflow_run SET completed_at = now()
    WHERE status IN ('completed', 'failed', 'cancelled') AND completed_at IS NULL;
CREATE INDEX idx_workflow_run_artifacts_unexpired
    ON workflow_run(completed_at) WHERE artifacts_expired_at IS NULL AND completed_at IS NOT NULL;

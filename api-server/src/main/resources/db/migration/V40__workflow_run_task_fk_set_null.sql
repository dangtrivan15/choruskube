-- V2 declared task_id with no ON DELETE action, so deleting a Task that ever had a run failed, and
-- so did deleting its Story or Epic, whose cascade reaches it. Its runs stay as unlinked history,
-- as story_id (V35) already does for a deleted Story.
ALTER TABLE workflow_run
    DROP CONSTRAINT workflow_run_task_id_fkey,
    ADD CONSTRAINT workflow_run_task_id_fkey FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE SET NULL;

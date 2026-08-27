ALTER TABLE task ADD COLUMN priority work_item_priority NOT NULL DEFAULT 'medium';
CREATE INDEX idx_task_priority ON task (priority);

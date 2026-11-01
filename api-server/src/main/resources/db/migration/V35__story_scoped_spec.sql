-- Story-scoped spec: a Story's approved spec is versioned in its own table, and a Task run that
-- finds the spec wrong files a conflict against the version it read. story_id is set only on
-- Story Design runs; Task runs keep task_id.
ALTER TABLE workflow_run ADD COLUMN story_id UUID REFERENCES story(id) ON DELETE SET NULL;
ALTER TABLE workflow_run ADD COLUMN story_spec_version INTEGER;
CREATE INDEX idx_workflow_run_story_id ON workflow_run(story_id) WHERE story_id IS NOT NULL;

CREATE TABLE story_spec_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id UUID NOT NULL REFERENCES story(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version >= 1),
    content TEXT NOT NULL,
    run_id UUID UNIQUE REFERENCES workflow_run(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (story_id, version));

CREATE TABLE story_conflict (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id UUID NOT NULL REFERENCES story(id) ON DELETE CASCADE,
    task_run_id UUID NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    analyze_node_execution_id UUID NOT NULL UNIQUE REFERENCES node_execution(id) ON DELETE CASCADE,
    raised_against_version INTEGER NOT NULL,
    report TEXT NOT NULL,
    revision_run_id UUID REFERENCES workflow_run(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ);
CREATE INDEX idx_story_conflict_open ON story_conflict(story_id) WHERE released_at IS NULL;

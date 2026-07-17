CREATE TYPE public.work_item_status AS ENUM ('backlog', 'in_progress', 'done');

CREATE TABLE public.epic (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    motivation TEXT,
    software_project_id UUID NOT NULL REFERENCES public.software_project(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_epic_software_project_id ON public.epic (software_project_id);
CREATE INDEX idx_epic_created_at ON public.epic (created_at DESC);

CREATE TABLE public.story (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    epic_id UUID NOT NULL REFERENCES public.epic(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_story_epic_id ON public.story (epic_id);

CREATE TABLE public.task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id UUID NOT NULL REFERENCES public.story(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    software_project_id UUID NOT NULL REFERENCES public.software_project(id),
    status work_item_status NOT NULL DEFAULT 'backlog',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_story_id ON public.task (story_id);
CREATE INDEX idx_task_status ON public.task (status);

ALTER TABLE public.workflow_run ADD COLUMN task_id UUID REFERENCES public.task(id);
CREATE INDEX idx_workflow_run_task_id ON public.workflow_run (task_id) WHERE task_id IS NOT NULL;

-- Wrap each existing proposal as Epic -> Story -> Task, preserving epic.id = feature_proposal.id
INSERT INTO public.epic (id, title, description, motivation, software_project_id, created_at, updated_at)
SELECT id, title, description, motivation, software_project_id, created_at, updated_at
FROM public.feature_proposal;

INSERT INTO public.story (id, epic_id, title, description, created_at, updated_at)
SELECT gen_random_uuid(), id, title, description, created_at, updated_at
FROM public.feature_proposal;

INSERT INTO public.task (id, story_id, title, description, software_project_id, status, created_at, updated_at)
SELECT gen_random_uuid(), s.id, fp.title, fp.description, fp.software_project_id,
       CASE fp.status WHEN 'rolled_out' THEN 'done'::work_item_status
                      ELSE fp.status::text::work_item_status END,
       fp.created_at, fp.updated_at
FROM public.feature_proposal fp
JOIN public.story s ON s.epic_id = fp.id;

UPDATE public.workflow_run wr
SET task_id = t.id
FROM public.task t
JOIN public.story s ON s.id = t.story_id
JOIN public.feature_proposal fp ON fp.id = s.epic_id
WHERE fp.workflow_run_id = wr.id;

-- NOTE: `feature_proposal` / `feature_proposal_status` are deliberately NOT dropped here,
-- deviating from this migration's originally-planned SQL (which did `DROP TABLE`/`DROP TYPE`).
-- A private downstream overlay's own already-shipped Flyway baseline (immutable per this repo's
-- migration conventions) creates a `feature_proposal_ownership` table with a foreign key to
-- `public.feature_proposal(id)`. Because that overlay always runs its entire migration sequence
-- only after this repo's Flyway sequence has fully completed, dropping this table here would make
-- the overlay's own baseline fail on every fresh install with "relation public.feature_proposal
-- does not exist" — before the overlay's own later migration (which retires
-- `feature_proposal_ownership` in favor of equivalent ownership tables for the new hierarchy)
-- ever gets a chance to run. The table and enum are left in place, empty and unreferenced by any
-- application code after this migration (FeatureProposal/FeatureProposalStatus are deleted from
-- the Java model), as inert leftovers. Revisit only alongside a coordinated cross-repo
-- migration-ordering change.

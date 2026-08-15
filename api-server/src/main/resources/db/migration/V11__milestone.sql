CREATE TABLE public.milestone (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    software_project_id UUID NOT NULL REFERENCES public.software_project(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    target_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_milestone_project_lower_name ON public.milestone (software_project_id, lower(name));
CREATE INDEX idx_milestone_software_project_id ON public.milestone (software_project_id);

ALTER TABLE public.epic
    ADD COLUMN milestone_id UUID REFERENCES public.milestone(id) ON DELETE SET NULL;
CREATE INDEX idx_epic_milestone_id ON public.epic (milestone_id) WHERE milestone_id IS NOT NULL;

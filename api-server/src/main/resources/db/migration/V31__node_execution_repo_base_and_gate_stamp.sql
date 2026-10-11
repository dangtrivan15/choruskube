-- Which default-branch commit each node execution's clone sat on, per repo. The approval gate
-- compares it with the repo's current default branch before merging anything.
CREATE TABLE public.node_execution_repo_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_execution_id UUID NOT NULL REFERENCES public.node_execution(id) ON DELETE CASCADE,
    git_repo_id UUID NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('clean', 'conflict', 'unavailable')),
    base_sha TEXT,
    conflicted_files JSONB NOT NULL DEFAULT '[]'::jsonb,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (node_execution_id, git_repo_id)
);

-- An approval held while its run re-checks against a moved default branch.
CREATE TABLE public.gate_stamp (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_run_id UUID NOT NULL REFERENCES public.workflow_run(id) ON DELETE CASCADE,
    gate_template_node_id UUID NOT NULL,
    gate_label TEXT NOT NULL,
    stamped_node_execution_id UUID NOT NULL REFERENCES public.node_execution(id) ON DELETE CASCADE,
    feedback TEXT,
    approved_candidates JSONB,
    stamp_mode TEXT NOT NULL CHECK (stamp_mode IN ('always', 'if_unchanged')),
    recheck_rounds INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'consumed', 'voided')),
    void_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Two pending stamps for one gate would re-apply the same approval twice.
CREATE UNIQUE INDEX uq_gate_stamp_pending ON public.gate_stamp (workflow_run_id, gate_template_node_id)
    WHERE status = 'pending';
CREATE INDEX idx_gate_stamp_run ON public.gate_stamp (workflow_run_id);

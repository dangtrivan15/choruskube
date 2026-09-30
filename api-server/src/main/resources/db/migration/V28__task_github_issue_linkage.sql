CREATE TYPE public.github_issue_state AS ENUM ('open', 'closed');

-- One row per Task the server filed a matching GitHub issue for (roadmap-extension
-- materialization only) — DefaultTaskService looks this up on Task completion to close the issue.
CREATE TABLE public.task_github_issue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL UNIQUE REFERENCES public.task(id) ON DELETE CASCADE,
    git_repo_id UUID NOT NULL,
    issue_number INTEGER NOT NULL,
    issue_url TEXT NOT NULL,
    state public.github_issue_state NOT NULL DEFAULT 'open',
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

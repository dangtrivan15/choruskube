-- The default branch the pod rebased onto, which the approval gate compares against. Null for
-- records from agent images that predate it; the gate then falls back to git_repo.default_branch.
ALTER TABLE public.node_execution_repo_base ADD COLUMN default_branch TEXT;

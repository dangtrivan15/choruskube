-- Optional per-project memory requests for the agent container and the dind sidecar, as Kubernetes
-- quantity strings. Null = the deployment default. Limits are never set per project.
ALTER TABLE public.git_repo ADD COLUMN agent_memory_request varchar(32);
ALTER TABLE public.git_repo ADD COLUMN dind_memory_request varchar(32);
ALTER TABLE public.repo_group ADD COLUMN agent_memory_request varchar(32);
ALTER TABLE public.repo_group ADD COLUMN dind_memory_request varchar(32);

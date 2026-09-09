-- Explicit per-project Docker toggle for composite Software Projects, mirroring git_repo.enable_docker.
ALTER TABLE public.repo_group ADD COLUMN enable_docker boolean DEFAULT false NOT NULL;

-- Backfill preserves the pre-explicit derived value (a group had Docker if any member did); runtime
-- inference from members is dropped hereafter and this column is authoritative.
UPDATE public.repo_group SET enable_docker = true
WHERE id IN (
    SELECT DISTINCT m.repo_group_id
    FROM public.repo_group_member m
    JOIN public.git_repo g ON m.git_repo_id = g.id
    WHERE g.enable_docker = true
);

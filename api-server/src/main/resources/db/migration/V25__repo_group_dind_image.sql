-- Optional per-project custom dind sidecar image ref. Null = platform default dind image.
ALTER TABLE public.repo_group ADD COLUMN dind_image varchar(512);

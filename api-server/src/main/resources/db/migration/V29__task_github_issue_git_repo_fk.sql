-- V28 left task_github_issue.git_repo_id without a foreign key, unlike every other
-- git_repo_id column in this schema (run_pull_request, repo_group_member). Without it,
-- deleting a GitRepo leaves a linkage row pointing at nothing; DefaultTaskService's
-- close-on-completion lookup then throws NotFoundException, which its catch-all logs
-- and swallows, so the issue is silently stuck open with no retry path.
ALTER TABLE ONLY public.task_github_issue
    ADD CONSTRAINT task_github_issue_git_repo_id_fkey FOREIGN KEY (git_repo_id) REFERENCES public.git_repo(id);

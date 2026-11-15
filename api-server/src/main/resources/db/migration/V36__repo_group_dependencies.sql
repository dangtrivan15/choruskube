ALTER TABLE repo_group ADD COLUMN test_affected_only boolean NOT NULL DEFAULT false;

CREATE TABLE repo_group_dependency (
    repo_group_id uuid NOT NULL REFERENCES repo_group(id) ON DELETE CASCADE,
    git_repo_id uuid NOT NULL REFERENCES git_repo(id),
    depends_on_git_repo_id uuid NOT NULL REFERENCES git_repo(id),
    PRIMARY KEY (repo_group_id, git_repo_id, depends_on_git_repo_id),
    CONSTRAINT chk_repo_group_dependency_not_self CHECK (git_repo_id <> depends_on_git_repo_id)
);

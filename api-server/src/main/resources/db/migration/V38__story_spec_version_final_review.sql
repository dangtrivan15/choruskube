-- A version's final review, kept with it so a revision never reads an earlier run's storage.
-- review_captured_at NULL means not captured yet; set with final_review NULL means the run had none.
ALTER TABLE story_spec_version ADD COLUMN final_review TEXT;
ALTER TABLE story_spec_version ADD COLUMN review_captured_at TIMESTAMPTZ;
ALTER TABLE story_spec_version ADD CONSTRAINT story_spec_version_review_captured
    CHECK (final_review IS NULL OR review_captured_at IS NOT NULL);
CREATE INDEX idx_story_spec_version_review_uncaptured
    ON story_spec_version(run_id) WHERE review_captured_at IS NULL;

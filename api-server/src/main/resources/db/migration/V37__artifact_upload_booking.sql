CREATE TABLE artifact_upload_booking (
    workflow_run_id uuid NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    object_key text NOT NULL,
    booked_bytes bigint NOT NULL CHECK (booked_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_run_id, object_key)
);

CREATE TABLE public.work_item_dependency (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocking_item_type VARCHAR(16) NOT NULL CHECK (blocking_item_type IN ('story', 'task')),
    blocking_item_id UUID NOT NULL,
    blocked_item_type VARCHAR(16) NOT NULL CHECK (blocked_item_type IN ('story', 'task')),
    blocked_item_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT work_item_dependency_no_self_reference CHECK (
        NOT (blocking_item_type = blocked_item_type AND blocking_item_id = blocked_item_id)
    ),
    CONSTRAINT work_item_dependency_unique UNIQUE (
        blocking_item_type, blocking_item_id, blocked_item_type, blocked_item_id
    )
);

CREATE INDEX idx_work_item_dependency_blocking ON public.work_item_dependency (blocking_item_type, blocking_item_id);
CREATE INDEX idx_work_item_dependency_blocked ON public.work_item_dependency (blocked_item_type, blocked_item_id);

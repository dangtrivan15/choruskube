ALTER TABLE public.story
    ADD COLUMN stage work_item_status NOT NULL DEFAULT 'backlog';

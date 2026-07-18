ALTER TYPE public.work_item_status ADD VALUE 'rolled_out';

ALTER TABLE public.epic
    ADD COLUMN stage work_item_status NOT NULL DEFAULT 'backlog';

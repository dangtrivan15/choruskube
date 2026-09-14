ALTER TABLE public.autopilot ADD COLUMN max_awaiting_human INT NOT NULL DEFAULT 0 CHECK (max_awaiting_human >= 0);

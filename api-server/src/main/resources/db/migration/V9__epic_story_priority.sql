CREATE TYPE public.work_item_priority AS ENUM ('low', 'medium', 'high');

ALTER TABLE public.epic
    ADD COLUMN priority work_item_priority NOT NULL DEFAULT 'medium';

ALTER TABLE public.story
    ADD COLUMN priority work_item_priority NOT NULL DEFAULT 'medium';

CREATE INDEX idx_epic_priority ON public.epic (priority);
CREATE INDEX idx_story_priority ON public.story (priority);

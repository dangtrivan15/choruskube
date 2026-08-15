-- Widen the dependency tier from story|task to epic|story|task.
--
-- blocking_item_type/blocked_item_type are plain VARCHAR(16) with inline CHECK constraints
-- (V5), not a Postgres enum type, so this is a constraint swap rather than ALTER TYPE.
-- V5 declared them inline and unnamed, so Postgres auto-named them <table>_<column>_check;
-- IF EXISTS keeps this migration safe if that ever differed.
ALTER TABLE public.work_item_dependency
    DROP CONSTRAINT IF EXISTS work_item_dependency_blocking_item_type_check,
    DROP CONSTRAINT IF EXISTS work_item_dependency_blocked_item_type_check;

ALTER TABLE public.work_item_dependency
    ADD CONSTRAINT work_item_dependency_blocking_item_type_check
        CHECK (blocking_item_type IN ('epic', 'story', 'task')),
    ADD CONSTRAINT work_item_dependency_blocked_item_type_check
        CHECK (blocked_item_type IN ('epic', 'story', 'task'));

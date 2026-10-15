-- The GitHub issues an approval's roadmap write asked the run's Worker to file. Null until the
-- finalize write step runs; complete reads null as the one-step protocol.
ALTER TABLE public.gate_stamp ADD COLUMN issue_requests JSONB;

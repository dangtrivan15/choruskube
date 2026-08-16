-- Ownership of one Autopilot pass, held as a lease on the row itself.
--
-- The tick used to serialise on pg_advisory_xact_lock(autopilot.id). That lock is
-- TRANSACTION-scoped, so once the tick stopped being one long transaction it was
-- released the moment the settle phase committed — leaving the planning, starting
-- and reporting phases unprotected. Two api-server instances would then both count
-- the same free slots and both start work, breaking max_parallel, which is the
-- feature's headline guarantee. Re-checking slots per start does not close it:
-- both instances re-check concurrently and both still see capacity.
--
-- A lease instead of a session-scoped advisory lock, for two reasons. A session
-- lock pins a pooled connection for the length of a pass and wedges the Autopilot
-- permanently if a release is ever missed; an expired lease is self-healing. And
-- this is ordinary SQL rather than a Postgres-specific function, so a LockService
-- on another engine stays tractable.
--
-- Both columns are nullable and start NULL: no lease held, anyone may take one.
ALTER TABLE public.autopilot
    ADD COLUMN tick_owner       TEXT,
    ADD COLUMN tick_lease_until TIMESTAMPTZ;

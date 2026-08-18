-- Drop the pre-hierarchy `feature_proposal` table and its enum.
--
-- V2 replaced the flat proposal model with Epic -> Story -> Task but deliberately kept these rows
-- (see its trailing NOTE), because V2's own CASE had flattened `rolled_out` into `task.status =
-- 'done'` and lost the distinction at the Epic level. V4 then recovered it by joining back on the
-- preserved `epic.id = feature_proposal.id`. That join was the table's whole remaining purpose,
-- and it has now served it: V4 restored the Epic tier, and the Story tier — which had no prior
-- status to recover, as V8 explains — was reconciled to its true lane out of band.
--
-- Nothing reads these rows any more. There is no entity, repository or service bound to this
-- table, and the `/feature-proposals` endpoints on InternalRunController are name-only
-- compatibility shims that already operate on Epics.
--
-- Ordering note: this is only droppable because migration execution interleaves the core and cloud
-- streams (see the cloud overlay's InterleavedFlywayConfig). The cloud overlay's V1 creates a
-- foreign key onto this table and its V3 drops that key again; both now run before this migration.
-- Under the previous all-core-then-all-cloud order, a from-scratch boot failed here. If that
-- interleaving is ever removed, this drop has to go with it.
--
-- What is deliberately given up: the provenance of the Epics sitting in `rolled_out`. After this,
-- their stage is a bare fact, with no record of whether a human, a backfill, or a legacy shipment
-- record put them there.

DROP TABLE IF EXISTS public.feature_proposal;

DROP TYPE IF EXISTS public.feature_proposal_status;

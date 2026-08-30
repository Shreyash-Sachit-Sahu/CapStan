-- Phase 07: remove every database-assigned timestamp.
--
-- A DEFAULT now() is a wall-clock read the application never sees. Under the
-- backtest's virtual clock that produces rows stamped with real time inside a
-- simulated 35-day cycle, and any comparison against them is meaningless.
--
-- payment_attempt.initiated_at is the load-bearing one: Reconciler.exhausted()
-- computes the reconcile budget against it. ExecutionStore.insertAttempt has
-- always written it explicitly, so the bug was latent rather than live -- which
-- is exactly why it is worth removing. A future insert that omitted the column
-- would silently reintroduce a wall-clock deadline into a virtual-time run, and
-- the six injected timeout traps would resolve at the wrong instant or never,
-- with nothing in the output to show it.
--
-- Callers now supply all three. The clock rule is enforced by grep in Java and,
-- from here, by the absence of any alternative in SQL.
ALTER TABLE payment_attempt ALTER COLUMN initiated_at DROP DEFAULT;
ALTER TABLE recovery_case   ALTER COLUMN created_at   DROP DEFAULT;
ALTER TABLE recovery_case   ALTER COLUMN updated_at   DROP DEFAULT;

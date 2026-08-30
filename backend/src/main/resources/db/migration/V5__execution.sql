-- Phase 05: execution engine -- outbox, claim leases, attempt states, events.

-- ---------------------------------------------------------------------------
-- Transactional outbox.
--
-- A decision commits an intent; publishing happens afterwards, so no AMQP or
-- HTTP call ever shares a transaction with a business write.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
  id           BIGSERIAL   PRIMARY KEY,
  aggregate_id UUID        NOT NULL,
  type         TEXT        NOT NULL,
  payload      JSONB       NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  attempts     INT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox (aggregate_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- At most one in-flight intervention per case.
--
-- The brief specified an exclusion constraint over
-- tstzrange(scheduled_for, expires_at) with btree_gist. Two things killed it.
-- A NULL expires_at does not exempt a row from that constraint, it makes the
-- range *unbounded*, so every pending row collided with everything else on its
-- case. And the rule we actually need is "at most one claimed at a time",
-- which is a unique index, not a range overlap -- the range form only buys
-- something if a case can hold a future window alongside an in-flight one, and
-- the policy engine emits one action at a time.
--
-- btree_gist stays installed and unused. Editing it out of V1 would mean a
-- Flyway repair on an applied migration, which is a worse trade than a dead
-- extension.
-- ---------------------------------------------------------------------------

-- expires_at changes meaning here. Phase 04 wrote it at decision time as
-- scheduled_for + 6h -- a *scheduled window*. It is now a *claim lease*: set by
-- the worker that claimed the row, cleared when the outcome lands. A scheduled
-- window and a claim lease are different things and the one name was carrying
-- both. Rows written under the old meaning would read as permanently in flight.
UPDATE intervention SET expires_at = NULL WHERE executed_at IS NULL;

CREATE UNIQUE INDEX uq_case_in_flight ON intervention (case_id) WHERE expires_at IS NOT NULL;

ALTER TABLE intervention ADD COLUMN cancel_reason TEXT;
ALTER TABLE intervention ADD CONSTRAINT ck_intervention_outcome
  CHECK (outcome IS NULL OR outcome IN
         ('SUCCEEDED','FAILED','UNKNOWN','SENT','RECONCILED','CANCELLED','SKIPPED'));

-- ---------------------------------------------------------------------------
-- payment_attempt.state was unconstrained TEXT, which is the permissive-default
-- shape this whole phase is about.
--
-- RECONCILED is deliberately not a state. An UNKNOWN that resolves becomes the
-- truth it resolved to; storing "RECONCILED" would record that it reconciled
-- but not to what. reconciled_at carries how we got there.
-- ---------------------------------------------------------------------------
ALTER TABLE payment_attempt ADD CONSTRAINT ck_attempt_state
  CHECK (state IN ('INITIATED','SUCCEEDED','FAILED','UNKNOWN'));

ALTER TABLE payment_attempt ADD COLUMN reconciled_at      TIMESTAMPTZ;
ALTER TABLE payment_attempt ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE payment_attempt ADD COLUMN next_reconcile_at  TIMESTAMPTZ;
ALTER TABLE payment_attempt ADD COLUMN failure_reason     TEXT;

CREATE INDEX idx_attempt_unresolved ON payment_attempt (next_reconcile_at)
  WHERE state IN ('INITIATED','UNKNOWN');

-- ---------------------------------------------------------------------------
-- Execution events.
--
-- Written with REQUIRES_NEW so they survive the rollback of the transaction
-- they describe: a debit that failed must still leave a record that it was
-- attempted. Phase 06 chains hashes over these rows rather than replacing them.
-- ---------------------------------------------------------------------------
CREATE TABLE execution_event (
  id      BIGSERIAL   PRIMARY KEY,
  case_id UUID        NOT NULL REFERENCES recovery_case(id),
  at      TIMESTAMPTZ NOT NULL,
  type    TEXT        NOT NULL,
  detail  JSONB       NOT NULL
);
CREATE INDEX idx_execution_event_case ON execution_event (case_id, id);

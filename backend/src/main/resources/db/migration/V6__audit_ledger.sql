-- Phase 06: the audit ledger.

-- ---------------------------------------------------------------------------
-- diagnosis_method was unconstrained TEXT, which let a value the database
-- accepted reach an enum that rejected it. A test fixture wrote 'RULE_MAP';
-- Postgres stored it happily and DiagnosisMethod.valueOf threw at read time,
-- inside a catch that logged and continued. Same permissive-default shape as
-- payment_attempt.state, constrained in V5.
-- ---------------------------------------------------------------------------
ALTER TABLE recovery_case ADD CONSTRAINT ck_diagnosis_method
  CHECK (diagnosis_method IS NULL
         OR diagnosis_method IN ('CODE_MAP', 'NARRATION', 'LLM', 'ABSTAIN'));

-- ---------------------------------------------------------------------------
-- execution_event grows into the chained trail rather than being replaced by a
-- second table. It already carries the REQUIRES_NEW write property Phase 05
-- proved, and one event log is easier to trust than two that must agree.
--
-- The existing rows are dropped rather than back-filled. They predate the chain
-- and there is no honest way to retro-chain them: their hashes would have to be
-- computed now and inserted as though they had been computed then. Fabricating
-- history in the one table whose entire purpose is being trustworthy is a worse
-- trade than starting the chain empty. The rows are artifacts of a local smoke
-- run, not recovered history.
-- ---------------------------------------------------------------------------
DELETE FROM execution_event;

ALTER TABLE execution_event RENAME TO audit_event;
ALTER SEQUENCE execution_event_id_seq RENAME TO audit_event_id_seq;
ALTER TABLE audit_event RENAME COLUMN at TO occurred_at;
ALTER TABLE audit_event RENAME COLUMN type TO event_type;
ALTER TABLE audit_event RENAME COLUMN detail TO payload;

-- occurred_at is written explicitly from the injected Clock and never defaulted.
-- A database-assigned timestamp would be a value the application never saw, and
-- the application is what computes the hash over it. (V5 declared no DEFAULT, so
-- there is nothing to drop -- this comment is the guard against adding one.)

ALTER TABLE audit_event ADD COLUMN seq       INT  NOT NULL;
ALTER TABLE audit_event ADD COLUMN actor     TEXT NOT NULL;
ALTER TABLE audit_event ADD COLUMN prev_hash TEXT NOT NULL;
ALTER TABLE audit_event ADD COLUMN hash      TEXT NOT NULL;

ALTER TABLE audit_event ADD CONSTRAINT uq_case_seq UNIQUE (case_id, seq);
ALTER TABLE audit_event ADD CONSTRAINT ck_seq_positive CHECK (seq >= 1);

DROP INDEX idx_execution_event_case;
CREATE INDEX idx_audit_case ON audit_event (case_id, seq);

-- ---------------------------------------------------------------------------
-- Append-only at the database level, not just by convention.
--
-- This is the first of two defences. It stops the application from editing the
-- trail at all; the hash chain is what catches anyone who gets past it. The
-- demo tamper endpoint has to DISABLE this trigger to corrupt a row, which
-- requires DDL privilege on the table -- and the chain still detects the edit.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_no_mutate() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_event is append-only';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_no_update BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_no_mutate();

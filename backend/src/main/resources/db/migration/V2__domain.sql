-- V2 — the domain every later phase writes into.
--
-- Three storage-level constraints carry claims made out loud in the demo. They
-- are load-bearing, not decoration:
--
--   uq_idem            a duplicate debit is not a bug we avoid, it is a row that
--                      cannot exist (Phase 05 derives the key deterministically)
--   uq_case_debit_seq  the debit cap cannot be exceeded by two racing workers,
--                      because the second insert fails (Phase 04 max_debit_attempts)
--   uq_case_attempt    one intervention per ladder position; weaker than the
--                      debit cap and a different claim -- a ladder mixes debits
--                      with nudges and re-auth links, so attempt_no counts
--                      positions, not debits
--
-- case_status is TEXT + CHECK rather than a native enum: the state machine is
-- still being built, and widening a CHECK is a one-line migration where
-- ALTER TYPE ... ADD VALUE carries real restrictions.

CREATE TABLE merchant_customer (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_ref      TEXT NOT NULL UNIQUE,
  contact_opted_out BOOLEAN NOT NULL DEFAULT FALSE,
  preferred_locale  TEXT NOT NULL DEFAULT 'en-IN'
);

CREATE TABLE mandate (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id      UUID NOT NULL REFERENCES merchant_customer(id),
  rail             TEXT NOT NULL,            -- UPI_AUTOPAY | CARD | ENACH
  valid_from       TIMESTAMPTZ NOT NULL,
  valid_until      TIMESTAMPTZ NOT NULL,
  max_amount_paise BIGINT NOT NULL,
  status           TEXT NOT NULL,            -- ACTIVE | REVOKED | EXPIRED
  alternate_rail   TEXT                      -- NULL if no fallback instrument
);

CREATE TABLE recovery_case (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  mandate_id           UUID NOT NULL REFERENCES mandate(id),
  customer_id          UUID NOT NULL REFERENCES merchant_customer(id),
  amount_paise         BIGINT NOT NULL CHECK (amount_paise > 0),
  currency             TEXT NOT NULL DEFAULT 'INR',
  billing_cycle_end    TIMESTAMPTZ NOT NULL,   -- hard stop: no action after this
  first_failed_at      TIMESTAMPTZ NOT NULL,
  status               TEXT NOT NULL DEFAULT 'OPEN'
    CONSTRAINT ck_case_status CHECK (status IN (
      'OPEN','DIAGNOSED','SCHEDULED','IN_FLIGHT',
      'RECOVERED','ABANDONED','ESCALATED','EXPIRED')),
  -- raw gateway signal, the only failure input the agent may read
  raw_error_code       TEXT NOT NULL,
  raw_error_desc       TEXT,
  raw_error_source     TEXT,
  raw_error_step       TEXT,
  bank_narration       TEXT,
  -- filled by Phase 03
  diagnosed_cause      TEXT,
  diagnosis_confidence NUMERIC(4,3),
  diagnosis_method     TEXT,                   -- CODE_MAP | LLM | ABSTAIN
  terminal_reason      TEXT,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  version              BIGINT NOT NULL DEFAULT 0   -- optimistic locking
);

CREATE INDEX idx_case_status_cycle ON recovery_case(status, billing_cycle_end);

-- Oracle: backtest-only. Never joined in agent code paths. OracleIsolationTest
-- fails the build if anything outside dev.capstan.backtest names this table.
CREATE TABLE case_oracle (
  case_id               UUID PRIMARY KEY REFERENCES recovery_case(id) ON DELETE CASCADE,
  recoverable           BOOLEAN NOT NULL,
  recovery_window_start TIMESTAMPTZ,
  required_channel      TEXT NOT NULL,
  nudge_sensitivity     NUMERIC(4,3) NOT NULL DEFAULT 0,
  attempt_success_prob  NUMERIC(4,3) NOT NULL DEFAULT 1.0,
  true_cause            TEXT NOT NULL,         -- diagnosis eval label
  true_state            TEXT                   -- e.g. DEBIT_ALREADY_SUCCEEDED
);

CREATE TABLE intervention (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id        UUID NOT NULL REFERENCES recovery_case(id),
  attempt_no     INT NOT NULL CHECK (attempt_no BETWEEN 1 AND 8),
  kind           TEXT NOT NULL,        -- see Phase 04 enum
  scheduled_for  TIMESTAMPTZ NOT NULL,
  -- End of the in-flight window. Phase 05 adds the exclusion constraint that
  -- stops two overlapping in-flight interventions on one case:
  --   EXCLUDE USING gist (case_id WITH =, tstzrange(scheduled_for, expires_at) WITH &&)
  -- The column lands here so that is an ADD CONSTRAINT, not an ALTER TABLE.
  expires_at     TIMESTAMPTZ,
  executed_at    TIMESTAMPTZ,
  outcome        TEXT,                 -- SUCCESS | FAILURE | SKIPPED | CANCELLED
  decision_json  JSONB NOT NULL,       -- full DecisionRecord, Phase 04
  CONSTRAINT uq_case_attempt UNIQUE (case_id, attempt_no)
);

CREATE TABLE payment_attempt (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id           UUID NOT NULL REFERENCES recovery_case(id),
  intervention_id   UUID NOT NULL REFERENCES intervention(id),
  -- Counts debits only, unlike intervention.attempt_no. This is the column the
  -- attempt-cap claim actually rests on.
  debit_seq         INT NOT NULL CHECK (debit_seq BETWEEN 1 AND 8),
  idempotency_key   TEXT NOT NULL,
  rail              TEXT NOT NULL,
  amount_paise      BIGINT NOT NULL,
  state             TEXT NOT NULL,    -- INITIATED|SUCCEEDED|FAILED|UNKNOWN|RECONCILED
  gateway_ref       TEXT,
  initiated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at        TIMESTAMPTZ,
  CONSTRAINT uq_idem UNIQUE (idempotency_key),
  CONSTRAINT uq_case_debit_seq UNIQUE (case_id, debit_seq)
);

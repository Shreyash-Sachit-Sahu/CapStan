-- V4 — the customer risk flag that G10 and the UNDIAGNOSED clearance branch read.
--
-- Both were written against a column that did not exist. A guardrail clause that
-- can never fire is worse than no guardrail: it shows up in Phase 07's activity
-- table as a zero, which reads as either dead code or a lie.
--
-- NOT NULL with a default is deliberate. The clearance branch treats an
-- *unavailable* risk answer as a failure to clear, so the column must never be
-- silently null for reasons unrelated to risk.

ALTER TABLE merchant_customer
  ADD COLUMN risk_flagged BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_customer_risk_flagged ON merchant_customer(risk_flagged)
  WHERE risk_flagged;

-- Phase 03 produces an evidence string ("narration matched 'INSUFF BAL'") that
-- Phase 04 copies into the DecisionRecord and Phase 08 renders in the why-panel.
-- Without it the audit trail says which tier decided but not what it saw.
ALTER TABLE recovery_case ADD COLUMN diagnosis_evidence TEXT;

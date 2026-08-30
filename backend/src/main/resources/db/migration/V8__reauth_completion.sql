-- Phase 07 follow-up: re-authorisation as an observable event.
--
-- Capstan sent a re-auth link and then had no rung that charged, so a customer
-- who completed the flow was never billed -- 25.0% of at-risk value on the
-- holdout batch. Adding a debit rung only means something if the policy can tell
-- whether the customer actually re-authorised, as opposed to merely having been
-- asked to. Guessing is exactly the behaviour the guardrails exist to prevent.
--
-- Modelled the way the real thing works: the merchant sends a link and later
-- observes a mandate re-registration. requested_at records the ask;
-- completed_at stays NULL unless the customer completed it. G12 reads both.
ALTER TABLE recovery_case ADD COLUMN reauth_requested_at TIMESTAMPTZ;
ALTER TABLE recovery_case ADD COLUMN reauth_completed_at TIMESTAMPTZ;

-- Completion cannot precede the request, and neither is database-assigned:
-- both are written from the injected clock so a backtest stamps virtual time.
ALTER TABLE recovery_case ADD CONSTRAINT ck_reauth_order
  CHECK (reauth_completed_at IS NULL
         OR (reauth_requested_at IS NOT NULL
             AND reauth_completed_at >= reauth_requested_at));

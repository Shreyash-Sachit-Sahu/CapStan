-- V3 — two columns V2 should have had.
--
-- raw_error_reason: Razorpay's error object splits `code` (a coarse class:
-- BAD_REQUEST_ERROR / GATEWAY_ERROR / SERVER_ERROR) from `reason` (the specific
-- machine-readable cause, e.g. insufficient_funds). V2 stored only `code`, which
-- meant the field carrying the actual signal was missing and the Tier 1 lookup
-- would have keyed on the wrong thing.
--
-- batch_label: the eval and backtest endpoints take ?batch=v1, but nothing
-- recorded which batch a case came from, so the parameter would have silently
-- scored whatever happened to be loaded.

ALTER TABLE recovery_case ADD COLUMN raw_error_reason TEXT;
ALTER TABLE recovery_case ADD COLUMN batch_label      TEXT;

CREATE INDEX idx_case_batch_label ON recovery_case(batch_label);

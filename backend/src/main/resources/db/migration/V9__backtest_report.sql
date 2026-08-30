-- Phase 08: persist what a run produced, so the cockpit can read it.
--
-- POST /api/backtest/run is synchronous and takes about eighty seconds. The
-- brief assumed it took "seconds" and could be polled for status; it is neither
-- pollable nor fast. Without somewhere to keep the result, opening the dashboard
-- would either trigger a fresh eighty-second run or render nothing -- both are
-- demo failures on a nine-second attention budget.
--
-- One row per (kind, batch, variant). Latest write wins, because a report is a
-- snapshot of the most recent measurement rather than a history: the audit
-- ledger is where history lives, and it is append-only precisely so this table
-- does not have to be.
CREATE TABLE backtest_report (
  kind       TEXT        NOT NULL,   -- run | sweep | ablations | budget
  batch      TEXT        NOT NULL,
  variant    TEXT        NOT NULL DEFAULT '',
  body       JSONB       NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (kind, batch, variant)
);

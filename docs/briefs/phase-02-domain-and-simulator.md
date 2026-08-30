# Capstan · Phase 02 — Domain Model + Failure Simulator

**Goal:** the schema every later phase writes into, plus a 300-record synthetic
batch of failed mandate debits carrying a *latent recoverability oracle* so
Phase 07 can measure recovery honestly.

**Done when:** `V2` migration applies, `simulator/generate.py` emits
`fixtures/batch_v1.json` with 300 cases, and a loader endpoint ingests them into
`recovery_case` with cause fields left NULL (Phase 03 fills those).

---

## 1. Why the oracle matters

You cannot measure "money recovered" without knowing what *would* have happened.
Real data would give you the ground truth for the actions actually taken and
nothing for the counterfactual. So the simulator assigns each case a hidden
recoverability function, and both the baseline and Capstan are evaluated against
the same function. The comparison is then fair, and the absolute rupee figure is
clearly labeled as synthetic.

**Latent model per case** (`oracle` block, never exposed to the agent):

- `recoverable: bool` — can this case ever succeed?
- `recovery_window_start: timestamptz` — earliest instant an attempt succeeds
  (e.g. salary credit date for insufficient funds; NULL if immediately recoverable)
- `required_channel: enum` — `ANY` | `SAME_RAIL` | `ALTERNATE_RAIL` |
  `REAUTH_REQUIRED` (a revoked mandate only recovers after a re-auth intervention)
- `nudge_sensitivity: float [0,1]` — probability a customer nudge pulls
  `recovery_window_start` earlier (models "customer tops up after being told")
- `attempt_success_prob: float` — for issuer-flakiness cases, per-attempt success
  probability once inside the window

The oracle is stored in a separate table `case_oracle` that the diagnosis and
policy modules have **no repository access to**. Enforce this by package
visibility — `dev.capstan.backtest` is the only package importing it. Write this
in the README; a judge asking "how do you know it's not cheating" gets a
one-sentence answer.

---

## 2. Cause distribution (realistic Indian recurring-payments mix)

Generate with roughly these proportions. They are not arbitrary — insufficient
funds dominates real subscription churn, and `DO_NOT_HONOUR` is the ambiguous
bucket that makes the diagnosis problem non-trivial.

| Cause | Share | Recoverable? |
|---|---|---|
| `INSUFFICIENT_FUNDS` | 32% | yes, after refill window |
| `ISSUER_DECLINE_TEMPORARY` | 12% | yes, per-attempt probability |
| `NETWORK_TIMEOUT` | 8% | yes, often already succeeded (see §5) |
| `DO_NOT_HONOUR` | 11% | 40% of these are recoverable — deliberately ambiguous |
| `CARD_EXPIRED` | 9% | only via `REAUTH_REQUIRED` |
| `MANDATE_LIMIT_EXCEEDED` | 7% | only via `REAUTH_REQUIRED` |
| `MANDATE_REVOKED` | 6% | no |
| `AUTHENTICATION_FAILED` | 5% | via `ALTERNATE_RAIL` or re-auth |
| `ACCOUNT_CLOSED` | 4% | no |
| `RISK_BLOCKED` | 3% | no — and must never be retried |
| `MANDATE_EXPIRED` | 2% | only via `REAUTH_REQUIRED` |
| `TECHNICAL_DECLINE_UNKNOWN` | 1% | 50/50 |

`MANDATE_EXPIRED` is here because Phase 03's taxonomy has twelve causes. Omitting
it left an unreachable ladder branch in Phase 04 and a permanently empty row in
the confusion matrix; the 2% comes off `INSUFFICIENT_FUNDS`.

Ticket values: log-normal, median ₹499, tail to ₹14,999 (so recovery-rate and
money-recovered can diverge — a good agent should prioritize by expected value,
and Phase 07 should show that they diverge).

**Critical:** the generator emits *real Razorpay error payloads*, not the cause
label. Each record carries `error_code`, `error_reason`, `error_description`,
`error_source`, `error_step`, and `bank_narration`.

Every `error_reason` value is taken from Razorpay's published lists — the
e-mandate subsequent-payment errors plus the general payments error list. None
are invented. The `code`/`reason` split matters and is easy to get backwards:
`code` is a coarse class (`BAD_REQUEST_ERROR`, `GATEWAY_ERROR`, `SERVER_ERROR`)
while `reason` carries the specific cause in lowercase snake_case
(`insufficient_funds`, `mandate_not_active`, `payment_failed`). `source` is one of
`customer | business | bank | gateway | razorpay`. See Phase 03 §3 for the
sources and the full mapping.

Two consequences worth designing around, both of which come from the real
taxonomy rather than being manufactured:

- **`mandate_not_active` covers both revoked and expired.** Razorpay does not
  distinguish them, and the payload contains nothing that does. Phase 03 resolves
  it deterministically from `mandate.valid_until`, not by asking the classifier.
- **~15% of records are *obscured*:** the bank declined without disclosing why, so
  the reason is generic (`payment_failed` / `payment_declined`) regardless of what
  actually went wrong. That is the population Tier 1 cannot resolve. Of those,
  three quarters carry a narration that still reveals the cause — half of them in
  conversational Hinglish that Tier 2's shorthand rules will not match — and the
  remainder are genuinely undecidable from the payload. The manifest reports that
  count as `undecidable_from_payload` and the resulting `accuracy_ceiling`, so
  Phase 03 is not measured against a target that cannot be reached.

Include Hinglish and bank-shorthand narrations for realism: `"INSUFF BAL"`,
`"TXN DECLINED BY ISSUER — DO NOT HONOUR"`, `"limit exceed hai"`,
`"AUTH TIMEOUT AT ACQUIRER"`, `"balance nahi hai"`, `"bank ne mana kar diya"`.

---

## 3. V2__domain.sql — key tables

Write full DDL; these are the load-bearing constraints.

`case_status` is `TEXT` + `CHECK`, not a native enum. The state machine is still
being built and Phase 05 is likely to want another state (`RECONCILING` is the
obvious candidate); widening a `CHECK` is a one-line migration where
`ALTER TYPE ... ADD VALUE` carries real restrictions. It also removes the
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` requirement that a native enum would impose
on every JPA entity once `ddl-auto: validate` starts checking them.

```sql
CREATE TABLE merchant_customer (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_ref      TEXT NOT NULL UNIQUE,
  contact_opted_out BOOLEAN NOT NULL DEFAULT FALSE,
  preferred_locale  TEXT NOT NULL DEFAULT 'en-IN'
);

CREATE TABLE mandate (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id    UUID NOT NULL REFERENCES merchant_customer(id),
  rail           TEXT NOT NULL,            -- UPI_AUTOPAY | CARD | ENACH
  valid_from     TIMESTAMPTZ NOT NULL,
  valid_until    TIMESTAMPTZ NOT NULL,
  max_amount_paise BIGINT NOT NULL,
  status         TEXT NOT NULL,            -- ACTIVE | REVOKED | EXPIRED
  alternate_rail TEXT                      -- NULL if no fallback instrument
);

CREATE TABLE recovery_case (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  mandate_id          UUID NOT NULL REFERENCES mandate(id),
  customer_id         UUID NOT NULL REFERENCES merchant_customer(id),
  amount_paise        BIGINT NOT NULL CHECK (amount_paise > 0),
  currency            TEXT NOT NULL DEFAULT 'INR',
  billing_cycle_end   TIMESTAMPTZ NOT NULL,   -- hard stop: no action after this
  first_failed_at     TIMESTAMPTZ NOT NULL,
  status              TEXT NOT NULL DEFAULT 'OPEN'
    CONSTRAINT ck_case_status CHECK (status IN (
      'OPEN','DIAGNOSED','SCHEDULED','IN_FLIGHT',
      'RECOVERED','ABANDONED','ESCALATED','EXPIRED')),
  -- raw gateway signal, the only failure input the agent may read
  raw_error_code      TEXT NOT NULL,
  raw_error_desc      TEXT,
  raw_error_source    TEXT,
  raw_error_step      TEXT,
  raw_error_reason    TEXT,                   -- Razorpay's specific cause (V3)
  bank_narration      TEXT,
  batch_label         TEXT,                   -- which batch this case came from (V3)
  -- filled by Phase 03
  diagnosed_cause     TEXT,
  diagnosis_confidence NUMERIC(4,3),
  diagnosis_method    TEXT,                   -- CODE_MAP | NARRATION | LLM | ABSTAIN
  terminal_reason     TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  version             BIGINT NOT NULL DEFAULT 0   -- optimistic locking
);

CREATE INDEX idx_case_status_cycle ON recovery_case(status, billing_cycle_end);

-- Oracle: backtest-only. Never joined in agent code paths.
CREATE TABLE case_oracle (
  case_id               UUID PRIMARY KEY REFERENCES recovery_case(id) ON DELETE CASCADE,
  recoverable           BOOLEAN NOT NULL,
  recovery_window_start TIMESTAMPTZ,
  required_channel      TEXT NOT NULL,
  nudge_sensitivity     NUMERIC(4,3) NOT NULL DEFAULT 0,
  attempt_success_prob  NUMERIC(4,3) NOT NULL DEFAULT 1.0,
  true_cause            TEXT NOT NULL,         -- diagnosis eval label
  true_state            TEXT                   -- e.g. DEBIT_ALREADY_SUCCEEDED, see §5
);

CREATE TABLE intervention (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id        UUID NOT NULL REFERENCES recovery_case(id),
  attempt_no     INT NOT NULL CHECK (attempt_no BETWEEN 1 AND 8),
  kind           TEXT NOT NULL,        -- see Phase 04 enum
  scheduled_for  TIMESTAMPTZ NOT NULL,
  expires_at     TIMESTAMPTZ,          -- claim lease held by the executing worker;
                                       -- NULL unless in flight. Phase 05 §4
  executed_at    TIMESTAMPTZ,
  outcome        TEXT,                 -- SUCCESS | FAILURE | SKIPPED | CANCELLED
  decision_json  JSONB NOT NULL,       -- full DecisionRecord, Phase 04
  CONSTRAINT uq_case_attempt UNIQUE (case_id, attempt_no)
);

CREATE TABLE payment_attempt (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  case_id           UUID NOT NULL REFERENCES recovery_case(id),
  intervention_id   UUID NOT NULL REFERENCES intervention(id),
  debit_seq         INT NOT NULL CHECK (debit_seq BETWEEN 1 AND 8),  -- debits only
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
```

### The constraint that carries the demo

`uq_idem` is the double-charge guard at the storage layer. Combined with the
deterministic key formula in Phase 05, a duplicate debit is impossible even if
every layer above it misbehaves. Say this out loud in the demo.

`uq_case_debit_seq` is the stopping rule at the storage layer — the debit cap
cannot be exceeded by a race between two workers, because the second insert fails.

`uq_case_attempt` is a **different and weaker** guarantee: one intervention per
ladder position. It does not enforce the debit cap and must not be described as
if it does. A ladder mixes debits with nudges and re-auth links — the
`INSUFFICIENT_FUNDS` ladder is four positions of which two are debits — so
`attempt_no` counts positions while `max_debit_attempts` counts debits. Two
constraints, two claims, both true; the one to say out loud when a judge asks
about the attempt cap is `uq_case_debit_seq`.

---

## 4. Simulator (`simulator/generate.py`)

Plain Python, `numpy` + `faker`, seeded (`--seed 42`) so results are reproducible.

The simulator runs from its own venv, never the system Python. On the dev machine
`python` resolves to a Windows Store alias stub and `py` defaults to 3.13, so both
would fail or silently use the wrong interpreter. Versions are pinned in
`simulator/requirements.txt` so a regenerated batch is byte-identical anywhere.

```
# one-time setup
py -3.12 -m venv simulator/.venv
simulator/.venv/Scripts/python.exe -m pip install -r simulator/requirements.txt

# generate
simulator/.venv/Scripts/python.exe simulator/generate.py \
    --cases 300 \
    --seed 42 \
    --cycle-start 2026-09-01 \
    --out fixtures/batch_v1.json
```

Emit a JSON array where each element has `case`, `mandate`, `customer`, `oracle`.
Also emit `fixtures/batch_v1_manifest.json` recording seed, distribution actually
produced, and total at-risk rupees — this becomes a table in the README.

Generate a second, unseen batch (`--seed 1337 --out fixtures/batch_holdout.json`)
now. Phase 03's prompt engineering and Phase 04's policy tuning happen on
`batch_v1`; final reported numbers come from the holdout. Track 03 doesn't demand
a held-out set the way Track 02 does, but reporting one is free credibility.

---

## 5. Seed the timeout case deliberately

Of the `NETWORK_TIMEOUT` records, mark **6** with
`oracle.true_state = "DEBIT_ALREADY_SUCCEEDED"`. These are cases where the debit
went through at the bank but the gateway response was lost. A naive retry ladder
double-charges all six. Capstan reconciles and recovers them with zero extra
debits. Phase 07 reports this as its own line item —
*duplicate charges avoided: 6 / ₹X* — and Phase 09 demos one of them live.

---

## 6. Acceptance checks

```bash
simulator/.venv/Scripts/python.exe simulator/generate.py \
    --cases 300 --seed 42 --out fixtures/batch_v1.json
jq 'length' fixtures/batch_v1.json                  # 300
jq '[.[] | select(.oracle.recoverable)] | length' fixtures/batch_v1.json

curl -X POST localhost:8080/api/admin/batch/load \
     -H 'Content-Type: application/json' \
     --data @fixtures/batch_v1.json
# → { "loaded": 300, "atRiskPaise": ... }
```

Then verify the isolation rule holds:

```bash
grep -rn "case_oracle\|CaseOracle" backend/src/main/java \
  | grep -v "dev/capstan/backtest"
# must return nothing
```

This is enforced, not just checked by hand: `OracleIsolationTest` runs the same
scan as a JUnit test, so a violation fails `./mvnw test`. It scans source text
rather than bytecode on purpose — the likeliest way the isolation gets broken is
raw SQL naming the table in a repository, which an ArchUnit type-level rule
cannot see. The test also asserts it scanned a non-empty file set, so it cannot
pass vacuously if the working directory moves.

**What the rule forbids, precisely:** the `CaseOracle` type and the `case_oracle`
table name, anywhere outside `dev.capstan.backtest`. It does **not** forbid
depending on an interface that backtest owns. Phase 05 §5 relies on this: the
`SimulatedGateway` stands in for reality and reality knows the answer, but it
reaches that answer through `OracleOutcomeResolver` — a port owned and
implemented by `dev.capstan.backtest` — and never names the oracle itself.

The batch loader is in `dev.capstan.backtest` for the same reason: it writes
oracle rows, so it cannot live in `api` or `ingest` without tripping the rule.
Only the package is constrained; the URL stays `/api/admin/batch/load`.

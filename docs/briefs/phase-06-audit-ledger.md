# Capstan · Phase 06 — Audit Ledger

**Goal:** an append-only, tamper-evident record of every diagnosis, decision,
guardrail evaluation, dispatch, gateway response, and terminal transition —
queryable as a single per-case narrative.

Short phase. High return: "show the audit trail" is stated explicitly in two of
the five track bars.

**Done when:** `GET /api/cases/{id}/trail` returns the ordered chain,
`GET /api/ledger/verify` confirms chain integrity across the whole batch, and
deliberately mutating a row makes verification fail.

---

## 1. Schema

```sql
CREATE TABLE audit_event (
  id           BIGSERIAL PRIMARY KEY,
  case_id      UUID NOT NULL REFERENCES recovery_case(id),
  seq          INT  NOT NULL,          -- per-case sequence, starts at 1
  event_type   TEXT NOT NULL,
  actor        TEXT NOT NULL,          -- SYSTEM:diagnose | SYSTEM:policy | GATEWAY | HUMAN:<id>
  payload      JSONB NOT NULL,
  occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  prev_hash    TEXT NOT NULL,
  hash         TEXT NOT NULL,
  CONSTRAINT uq_case_seq UNIQUE (case_id, seq)
);

CREATE INDEX idx_audit_case ON audit_event(case_id, seq);

-- Append-only at the database level, not just by convention.
CREATE OR REPLACE FUNCTION audit_no_mutate() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_event is append-only';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_no_update BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_no_mutate();
```

The trigger is a five-line addition that turns "we log things" into "the log
cannot be edited, here is the enforcement." Cheap credibility.

---

## 2. Hash chain

```java
static String chainHash(UUID caseId, int seq, String type, String payloadJson,
                        Instant at, String prevHash) {
    String canonical = String.join("|",
        caseId.toString(), Integer.toString(seq), type,
        payloadJson,                       // must be canonically serialised
        at.toString(), prevHash);
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(UTF_8)));
}
```

Genesis `prev_hash` for `seq = 1` is `"0".repeat(64)`.

**Canonical JSON matters.** Use a serializer with sorted keys and no
insignificant whitespace, or verification will fail spuriously when Jackson
reorders a map. Configure one `ObjectMapper` bean dedicated to audit
serialization with `ORDER_MAP_ENTRIES_BY_KEYS` and
`SerializationFeature.INDENT_OUTPUT` disabled. Do not reuse the web MVC mapper.

---

## 3. Event types (the full case narrative)

```
CASE_OPENED
DIAGNOSIS_ATTEMPTED     { tier, method, latencyMs, cacheHit }
DIAGNOSIS_RESOLVED      { cause, confidence, evidence }
DECISION_MADE           { full DecisionRecord from Phase 04 }
GUARDRAIL_BLOCKED       { guardrailId, reason, proposedAction }
INTERVENTION_SCHEDULED  { kind, scheduledFor, attemptNo }
DEBIT_INITIATED         { idempotencyKey, rail, amountPaise }
GATEWAY_RESPONSE        { state, gatewayRef, rawStatus }
ATTEMPT_UNKNOWN         { idempotencyKey, reconcileBudget }
RECONCILE_ATTEMPTED     { idempotencyKey, resolvedState }
COMMS_SENT              { channel, template, locale, validatorPassed }
COMMS_SUPPRESSED        { guardrailId, reason }
INTERVENTION_CANCELLED  { reason }
CASE_TERMINATED         { status, terminalReason, recoveredPaise }
```

Note `COMMS_SUPPRESSED` and `GUARDRAIL_BLOCKED`. **The trail must record the
actions Capstan chose not to take.** A log that only shows what happened cannot
demonstrate that the system is bounded. This is the single most persuasive thing
in the ledger and it costs nothing to add.

---

## 4. Write path

Audit writes use `@Transactional(propagation = REQUIRES_NEW)` so they survive
rollback of the surrounding business transaction. A failed debit that rolls back
must still leave its `DEBIT_INITIATED` and `GATEWAY_RESPONSE` events behind —
otherwise the trail lies by omission exactly when it matters.

Sequence allocation races: two concurrent writers on the same case both compute
`seq = n+1`. `uq_case_seq` rejects the loser. Catch
`DataIntegrityViolationException`, re-read the tail, recompute, retry — cap at 5.
Write a test with two threads.

---

## 5. API

```
GET  /api/cases/{id}/trail        → ordered events + computed narrative summary
GET  /api/ledger/verify           → { casesChecked, chainsValid, firstBreak: null }
GET  /api/ledger/verify/{caseId}  → per-case verification with break position
POST /api/admin/tamper/{eventId}  → DEV PROFILE ONLY. Corrupts a payload so the
                                    demo can show verification failing.
```

Guard the tamper endpoint with `@Profile("demo")` and a startup log line stating
it is enabled. Never ship it in the default profile — and say so in the README,
because a judge who spots an unguarded tamper endpoint will assume the worst.

---

## 6. Narrative summary

For each case, render the chain into plain English for the cockpit's case view.
Deterministic templating, not LLM — the ledger is the one place where a
paraphrase must never drift:

```
14:02 IST  Debit of ₹499 failed — insufficient balance (issuer code, confidence 0.99).
14:02 IST  Held retry. Salary date inferred as the 1st; retrying now would spend
           1 of 3 permitted attempts against a balance we know is short.
01 Oct 09:00 IST  Retried on UPI Autopay. Succeeded. ₹499 recovered.
           Remaining permitted attempts: 2. Case closed.
```

---

## 7. Acceptance checks

```bash
curl -s localhost:8080/api/ledger/verify | jq
# { "casesChecked": 300, "chainsValid": 300, "firstBreak": null }

curl -X POST localhost:8080/api/admin/tamper/4021     # demo profile
curl -s localhost:8080/api/ledger/verify | jq '.firstBreak'
# { "caseId": "...", "seq": 3, "expectedHash": "...", "actualHash": "..." }

./mvnw test -Dtest=AuditAppendOnlyTriggerTest      # UPDATE raises
./mvnw test -Dtest=AuditSeqRaceTest                # 2 threads, no gap, no dupe
./mvnw test -Dtest=SuppressedActionsAreLoggedTest
```

# Capstan · Phase 05 — Execution Engine (Saga + Outbox + Idempotency)

**Goal:** actually execute the authorised intervention against the payment
gateway, exactly once, with reconciliation before every retry, DLQ-backed failure
handling, and a compensation path for the ambiguous-outcome case.

This is the longest phase and the one that makes the submission credible. Budget
accordingly.

**Done when:** the double-charge scenario is provably impossible — demonstrated by
an integration test that kills the response mid-flight and asserts exactly one
debit exists at the gateway.

---

## 1. The invariant

> **Capstan never initiates a debit whose outcome it has not first reconciled.**

Reconciliation asks the gateway what happened to the previous attempt, keyed by
the deterministic idempotency key. Only an attempt confirmed `SUCCEEDED` or
`FAILED` may be followed by a new debit. An attempt in state `UNKNOWN` blocks the
case until reconciliation resolves it, or escalates after the reconcile budget is
exhausted.

**This cannot come from the ladders, and an earlier draft of this brief was wrong
to imply it did.** Only `NETWORK_TIMEOUT` has a `RECONCILE_ONLY` rung; five other
ladders step straight from one debit to the next — `INSUFFICIENT_FUNDS` is
`[PAYDAY_RETRY, CUSTOMER_NUDGE, PAYDAY_RETRY, ABANDON]` and
`AUTHENTICATION_FAILED` is `[SCHEDULED_RETRY, SCHEDULED_RETRY, RAIL_SWITCH,
ABANDON]`. The invariant is therefore a **precondition on the one code path that
debits**: `ExecutionStore.requirePredecessorReconciled`, called by
`DebitWorker.debit` before anything else happens.

The check is affirmative — `state IN ('SUCCEEDED','FAILED')`, never
`NOT IN ('INITIATED')`. Written negatively it would let `UNKNOWN` through, and
letting an unknown attempt authorise the next debit is exactly how double charges
happen. `DebitGateTest` fails the build if a second call site to
`PaymentGateway.debit` appears, since a second call site is an unguarded
debit-to-debit transition.

State machine on `payment_attempt`:

```
        ┌──────────┐
        │ INITIATED│
        └────┬─────┘
   response? │
   ┌─────────┼──────────┐
   ▼         ▼          ▼
SUCCEEDED  FAILED    UNKNOWN ──reconcile──► SUCCEEDED | FAILED
   │                     │                       │
   │                     └── budget spent ───────┴──► ESCALATED
   ▼
case RECOVERED
```

`UNKNOWN` is a first-class state, not an error. Most systems collapse it into
`FAILED` and that is precisely where double charges come from.

Two corrections to that diagram. `ESCALATED` is a **case** status, not an attempt
state: budget exhaustion escalates the case while the attempt stays `UNKNOWN`,
which is the truth. And `RECONCILED` is deliberately *not* a state — it would
record that an attempt reconciled but not to what. An `UNKNOWN` that resolves
becomes the outcome it resolved to, and `reconciled_at` carries how we got there.
`payment_attempt.state` was unconstrained `TEXT` and now has a CHECK:
`INITIATED | SUCCEEDED | FAILED | UNKNOWN`.

---

## 2. Idempotency key — verbatim

Deterministic, so a retry of the *same* attempt reuses the same key, while a
*new* authorised attempt gets a new one. Backed by `uq_idem` from Phase 02.

```java
public static String idempotencyKey(UUID caseId, int attemptNo, String rail, long amountPaise) {
    String raw = "capstan:v1:%s:%d:%s:%d".formatted(caseId, attemptNo, rail, amountPaise);
    return "cap_" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8))
    ).substring(0, 40);
}
```

`attemptNo` is `payment_attempt.debit_seq`, not the ladder position. The key
identifies a debit, and a rung that sends a nudge or asks the gateway what
happened is not one.

**Do not send an `X-Idempotency-Key` header.** This brief originally said to,
and claimed Razorpay supports idempotency keys on payment creation. It does not.
The only idempotency header Razorpay documents is `X-Payout-Idempotency`, on
RazorpayX Create Payout and the Composite APIs — a different product and a
different flow. Sending an invented header would be worse than sending none: the
gateway ignores it while our own documentation implies it is being honoured
upstream.

Exactly-once here is **ours**. `uq_idem` on `payment_attempt` enforces it, and
that is the stronger claim anyway — the guarantee does not depend on the
gateway's cooperation. What Razorpay does give us is a reconciliation handle: an
order carries a merchant-supplied `receipt`, and `GET /v1/orders?receipt=`
fetches by it. Receipts cap at 40 characters and the key is 44, so the `cap_`
prefix comes off; the remaining 40 hex characters are still a bijection with the
key. For any other endpoint, use whatever Razorpay actually documents, and if
that is nothing, say so.

The key is stored *before* the call is made, inside the same transaction that
creates the `payment_attempt` row. If the process dies between insert and call,
recovery finds an `INITIATED` row and reconciles it.

---

## 3. Transactional outbox

Decision (Phase 04) and dispatch must not share a transaction with an HTTP call.

```sql
CREATE TABLE outbox (
  id             BIGSERIAL PRIMARY KEY,
  aggregate_id   UUID NOT NULL,
  type           TEXT NOT NULL,
  payload        JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ,
  attempts       INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
```

Poller — note the advisory lock so multiple instances don't double-publish, and
note that `@Scheduled` on a `public` method of a *different* bean than the one
doing the work avoids the self-invocation trap:

```java
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishBatch() {
        Boolean got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(918273)", Boolean.class);
        if (!Boolean.TRUE.equals(got)) return;

        List<OutboxRow> rows = jdbc.query("""
            SELECT id, aggregate_id, type, payload FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, OUTBOX_MAPPER);

        for (OutboxRow r : rows) {
            rabbit.convertAndSend("capstan.exchange", r.type(), r.payload());
            jdbc.update("UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE id = ?", r.id());
        }
    }
}
```

**Gotchas carried forward from Faraday/Relay:**
- `@Async` and `@Scheduled` are proxy-based. Calling an `@Async` method from
  within the same bean is a plain method call and silently runs synchronously.
  Cross-bean calls only.
- Any write that must survive a rollback of the business transaction (audit
  events, security records) uses `@Transactional(propagation = REQUIRES_NEW)`.
  This bit you on Faraday; it will bite here on audit writes during failed debits.
  Audit events must persist *even when the debit transaction rolls back.*

---

## 4. Queues and DLQ

```java
@Bean Queue debitQueue() {
    return QueueBuilder.durable("capstan.debit")
        .quorum()
        .deadLetterExchange("capstan.dlx")
        .deadLetterRoutingKey("capstan.debit.dead")
        .build();
}
@Bean Queue debitDlq() { return QueueBuilder.durable("capstan.debit.dead").quorum().build(); }
```

Consumer contract:
- Manual ack (set in Phase 01's `application.yml`).
- On transient failure (gateway 5xx, timeout): `nack(requeue=false)` after
  recording the attempt as `UNKNOWN`, and enqueue a delayed reconcile job. Do
  **not** requeue — `default-requeue-rejected: false` sends it to the DLX, which
  is what you want. Requeue loops are the classic RabbitMQ footgun.
- On permanent failure: record, terminate the case, ack.
- DLQ drain endpoint `POST /api/admin/dlq/replay` for the demo, which
  re-diagnoses and re-decides rather than blindly replaying — replaying a stale
  decision would bypass the guardrails.

That last point matters and judges will not expect it: **a DLQ replay re-enters
the policy engine, it does not re-execute the old action.** Guardrails might now
say stop.

### At most one in-flight intervention per case

The cooling-off guardrail (Phase 04 G6) is enforced in code. Back it at the
storage layer too, so two workers cannot both take the same case regardless of
what the policy engine decided:

```sql
CREATE UNIQUE INDEX uq_case_in_flight ON intervention (case_id)
  WHERE expires_at IS NOT NULL;
```

**This replaces the exclusion constraint this brief originally specified**
(`EXCLUDE USING gist (case_id WITH =, tstzrange(scheduled_for, expires_at) WITH &&)`).
Two things were wrong with it. A NULL `expires_at` does not exempt a row —
`tstzrange(x, NULL)` is *unbounded*, so every pending row collided with
everything else on its case, and the original text claiming such rows were
ignored had it backwards. And the rule we actually need is "at most one claimed
at a time", not "no overlapping ranges"; the range form only buys something if a
case can hold a future window alongside an in-flight one, and the policy engine
emits one action at a time.

`btree_gist` was installed in V1 for that constraint and is now unused. It stays
installed: editing V1 would mean a Flyway repair on an applied migration, which
is a worse trade than a dead extension. The honest version of the story is
better than the constraint would have been — we installed it for an exclusion
constraint, then found a partial unique index expressed the actual rule in one
line.

`expires_at` is a **claim lease**, not a scheduled window: the worker sets it in
the same statement that claims the row, and clears it when the outcome lands. It
is not written at decision time — see Phase 04 §5.

---

## 5. Gateway adapter

Two implementations behind one interface, selected by profile:

```java
public interface PaymentGateway {
    AttemptResult debit(DebitCommand cmd);           // idempotency key inside
    AttemptResult fetchByIdempotencyKey(String key); // reconciliation
    void sendCommunication(CommsCommand cmd);
}
```

- `RazorpayTestGateway` — real test-mode API calls. Use it for the live portion of
  the demo so "runs against Razorpay APIs" is literally true.
- `SimulatedGateway` — drives the 300-case backtest, resolves success/failure
  through the `OracleOutcomeResolver` port, and can be told to inject faults:
  `--inject timeout_after_debit=6`, `--inject issuer_down_window=45m`.

The simulator gateway is the only component outside `dev.capstan.backtest`
allowed to *reach* the oracle, and it does so through a narrow
`OracleOutcomeResolver` port. Document why: the gateway is standing in for
reality, and reality knows the answer. The *agent* still does not.

**Do not let the gateway name the oracle.** `OracleIsolationTest` (Phase 02 §6)
fails the build if the `CaseOracle` type or the `case_oracle` table name appears
anywhere outside `dev.capstan.backtest`, and that includes
`dev.capstan.gateway`. The port interface and its implementation both live in
`backtest`; the gateway depends on the interface only. Written any other way,
Phase 02's isolation rule and this section contradict each other and one of them
has to be wrong.

---

## 6. The compensation path (the demo centrepiece)

Scenario: `IMMEDIATE_RETRY` is dispatched. The gateway debits the customer. The
HTTP response is lost.

1. Consumer times out → writes `payment_attempt.state = UNKNOWN` in a
   `REQUIRES_NEW` transaction so it survives, nacks to DLQ.
2. `ReconcileScheduler` picks up the `UNKNOWN` attempt after 30s and calls
   `fetchByIdempotencyKey`.
3. Gateway returns the successful payment.
4. Attempt → `RECONCILED`/`SUCCEEDED`; case → `RECOVERED`; **any queued
   interventions for that case are cancelled** (`outcome = CANCELLED`, reason
   `superseded_by_reconciliation`).
5. Audit chain records the whole sequence.

Reconcile budget: 5 attempts over 30 minutes with exponential backoff. Exhausted →
`ESCALATED`, never `FAILED`. An unresolvable unknown is a human's problem, not a
retry's.

**Reachability note, measured in Phase 07.** This cancellation path never fires
during a backtest, and that is a design property rather than a gap. The tick
loop holds a case IN_FLIGHT while an attempt is unresolved, so the dispatcher
never queues a sibling intervention for the reconciler to supersede -- there were
**zero** cancelled interventions across the entire 300-case holdout run. The
mechanism is proven by NoDoubleChargeUnderTimeoutTest, which constructs the
queued retry explicitly. Phase 08 must not seed a case to make the cancelled row
appear on screen: a demo artefact showing something the system did not do is the
one dishonesty this project has spent nine phases avoiding.

Cancellation must be race-safe: cancel via a conditional update
(`UPDATE intervention SET outcome='CANCELLED' WHERE id=? AND executed_at IS NULL`)
and have the worker re-check case status after claiming a message. Belt and
braces, because this is the one path that must not fail on stage.

---

## 7. Acceptance checks

```bash
# The headline test
./mvnw test -Dtest=NoDoubleChargeUnderTimeoutTest
# Asserts: gateway received exactly 1 debit; case RECOVERED; queued retry CANCELLED

./mvnw test -Dtest=OutboxAtLeastOncePublishTest
./mvnw test -Dtest=DlqReplayReentersPolicyTest
./mvnw test -Dtest=AuditSurvivesRollbackTest        # REQUIRES_NEW proof
./mvnw test -Dtest=AttemptCapRaceTest               # two workers, one wins on uq_case_debit_seq
./mvnw test -Dtest=StaleAuthorisationTest           # terminated case, claimed message
./mvnw test -Dtest=DebitGateTest                    # exactly one debit call site
```

`AttemptCapRaceTest` originally said "one wins on `uq_case_attempt`". That is the
wrong constraint: `uq_case_attempt` is on `intervention` and caps ladder
positions. The debit cap is `uq_case_debit_seq` on `payment_attempt`, with
`uq_idem` catching the same race by a different route. (Same correction as the
one already applied to Phase 09's judge-answer section.)

The chaos run belongs to Phase 07, not here — `/api/backtest/run` is the backtest
runner. Phase 05 ships the fault injection it needs
(`SimulatedGateway.Faults`, reachable at `POST /api/execute/inject`) and drives it
from tests:

```bash
curl -X POST 'localhost:8080/api/execute/inject?spec=timeout_after_debit:6,issuer_down:45m'
```

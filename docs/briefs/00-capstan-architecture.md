# Capstan — Architecture Brief

**Razorpay AI Buildathon · Track 03: AI Revenue Recovery**

> A capstan is a ratcheted winch. It hauls a line back in under load, and a pawl
> prevents it from slipping backward. That is the product: pull revenue back,
> never let it slip, never over-pull.

---

## 1. The problem, narrowed

Recurring payments in India fail constantly and for boring reasons: insufficient
balance on the 28th, an expired card, a UPI Autopay mandate whose per-txn cap was
raised on the merchant side but not on the mandate, an issuer having a bad hour.

The standard merchant response is a fixed retry ladder — T+1, T+3, T+5 — fired at
every failure regardless of cause, plus a generic "your payment failed" SMS. This
is wrong in two directions at once. It burns attempts on failures that can never
succeed by retrying (revoked mandate, closed account), and it retries too early
on failures that would succeed if it simply waited for salary day.

**Capstan diagnoses each failure, selects a bounded intervention, executes it
safely, and stops when the rules say stop.** It reports how much money it
recovered against a baseline, and hands back an honest list of what it could not
resolve.

### Scope discipline

One loss channel: **failed recurring mandate debits** (UPI Autopay + card-on-file
subscriptions). Not checkout abandonment, not B2B receivables. Depth over breadth
— the track bar rewards measured recovery across a batch, not surface area.

---

## 2. The judging bar, mapped

Track 03's stated bar, and where each part is satisfied:

| Bar | Where |
|---|---|
| "measured money recovered across a batch" | Phase 07 backtest harness — 300-record batch, agent policy vs fixed-ladder baseline |
| "compliant escalation" | Phase 04 guardrail set — quiet hours, comms frequency caps, opt-out honoring, mandate-validity checks |
| "stopping rules" | Phase 04 terminal states + Phase 05 attempt caps enforced at the DB constraint level, not just in code |
| "audit trail" | Phase 06 hash-chained append-only decision log, queryable per recovery case |
| "don't just identify the problem" | Phase 05 real execution against Razorpay test-mode APIs |

### The differentiator

Most teams will ship: detect failure → LLM writes a nice WhatsApp message → done.

Capstan's demo moment is a **gateway timeout after the debit was initiated** —
the case where a naive retry double-charges a real customer. Capstan never blind-
retries. It reconciles by idempotency key against the gateway first, discovers the
debit actually succeeded, marks the case recovered, and cancels the queued retry.
That single sequence proves an execution engine exists behind the model.

---

## 3. System architecture

```
                        ┌──────────────────────────────┐
   failure events  ───► │  INGEST                      │
   (webhook / batch)    │  normalize gateway payloads  │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │  DIAGNOSE            (Ph 03) │
                        │  code map → LLM fallback     │
                        │  → FailureCause + confidence │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │  DECIDE              (Ph 04) │
                        │  policy table + guardrails   │
                        │  → Intervention | ABANDON    │
                        │  emits DecisionRecord        │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │  EXECUTE             (Ph 05) │
                        │  Saga orchestrator           │
                        │  outbox → RabbitMQ → workers │
                        │  idempotent, DLQ, compensate │
                        └──────────────┬───────────────┘
                                       │
                        ┌──────────────▼───────────────┐
                        │  LEDGER              (Ph 06) │
                        │  hash-chained audit events   │
                        └──────────────┬───────────────┘
                                       │
              ┌────────────────────────┴───────────────┐
              │                                        │
   ┌──────────▼──────────┐              ┌──────────────▼─────────┐
   │ BACKTEST     (Ph 07)│              │ COCKPIT         (Ph 08)│
   │ batch, metrics,     │              │ Next.js ops dashboard  │
   │ exception list      │              │ case timeline + why    │
   └─────────────────────┘              └────────────────────────┘
```

### Services

Single Spring Boot modular monolith, not microservices. A hackathon does not
reward distributed systems tax. Modules are package-isolated with explicit
interfaces so it reads as an architecture, not a pile.

```
capstan/
├── docker-compose.yml
├── backend/                    # Spring Boot 3.5.x, JDK 21
│   └── src/main/java/dev/capstan/
│       ├── ingest/
│       ├── diagnose/
│       ├── policy/
│       ├── execute/            # saga + outbox + workers
│       ├── ledger/
│       ├── backtest/
│       ├── gateway/            # Razorpay adapter + simulator
│       └── api/
├── frontend/                   # Next.js, Node v24
├── simulator/                  # Python — synthetic batch generator
└── docs/
```

### Stack (standard pins)

- JDK 21, Spring Boot 3.5.x, Spring Security 6, Spring Cloud 2025.x
- PostgreSQL 16, Flyway 10.x (with `flyway-database-postgresql`). `btree_gist` is
  installed in V1 but unused: it was there for an exclusion constraint that
  Phase 05 replaced with a partial unique index, and removing it would mean a
  Flyway repair on an applied migration.
- Redis 7 — rate limiting, quiet-hours token buckets, distributed locks
- RabbitMQ 3.13 — work queues with DLX, quorum queues
- Next.js on Node v24
- LLM: Gemini free tier (`gemini-flash-lite-latest`) for Tier 3 diagnosis and
  evidence drafting. Provider-agnostic behind `LlmClassifier`: an Anthropic
  implementation ships alongside it and selection is by which credential is
  present, so switching is an environment variable. The free tier was a cost
  constraint, not a technical preference — but it also means the whole cascade
  degrades cleanly to Tier 1 + Tier 2 at 0.88 with no external dependency at all.

---

## 4. Core domain model (one paragraph)

A **RecoveryCase** is opened when a mandate debit fails. It owns a `FailureCause`
(diagnosed), a sequence of **Interventions** (each with attempt number, scheduled
time, guardrail evaluation), a sequence of **PaymentAttempts** (each with a unique
idempotency key), and terminates in exactly one of `RECOVERED`, `ABANDONED`,
`ESCALATED`, or `EXPIRED`. Every state transition writes an immutable
**AuditEvent** whose hash chains to the previous event on the same case.

---

## 5. Phase map

| # | Phase | Output | Est. |
|---|---|---|---|
| 01 | Foundation | Compose stack up, Boot skeleton, Flyway green, UTC enforced | 2h |
| 02 | Domain + Simulator | Schema, 300-record synthetic batch with latent recovery oracle | 4h |
| 03 | Diagnosis | Cause taxonomy, deterministic mapper + LLM fallback, eval report | 4h |
| 04 | Policy engine | Intervention table, guardrails, stopping rules, DecisionRecord | 4h |
| 05 | Execution saga | Outbox, idempotency, DLQ, reconcile-before-retry, compensation | 6h |
| 06 | Audit ledger | Hash-chained events, `/cases/{id}/trail` API | 2h |
| 07 | Backtest harness | Baseline vs agent, money recovered, FP cost, exception list | 4h |
| 08 | Cockpit | Next.js dashboard — batch metrics, case timeline, why-panel | 5h |
| 09 | Demo + hardening | Double-charge scenario, README, submission pack | 3h |

Build strictly in order. Phases 03 and 04 are the "AI" of the submission; phases
05 and 06 are what make judges believe it. Do not skip 07 — an unmeasured
recovery agent is indistinguishable from every other team's.

---

## 6. Honesty commitments (write these into the README)

These are not disclaimers, they are the reason the numbers are believable.

1. **The simulator is the oracle.** Synthetic records carry a latent
   recoverability function (e.g. an insufficient-funds case has a hidden
   balance-refill date; any attempt after it succeeds). We disclose this and
   report the baseline against the same oracle, so the comparison is fair even
   though the absolute numbers are synthetic.
2. **We report the cost of being wrong.** Wasted attempts and wasted customer
   contacts are counted and shown next to recovery rate. A policy that recovers
   more by spamming is a worse policy.
3. **Guardrail constants are configurable, not legal advice.** Retry caps and
   quiet hours are set to defensible defaults with a note on where real NPCI /
   card-network / merchant-agreement limits would replace them.
4. **The exception list is published.** Every case Capstan could not resolve is
   listed with the reason. Cherry-picking is the failure mode the bar names.

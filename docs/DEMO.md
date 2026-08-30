# Capstan — demo script, shot list, and judge Q&A

Four minutes. Rehearse twice. **Record a fallback capture the night before**
regardless of how the live demo is meant to run — venue wifi is the most common
reason a working system demos badly.

Every number below is in the committed reports — `docs/report_holdout.json`
carries the sweep, the single-batch run, the ablations and the equal-budget
tables; `docs/report_diagnosis_holdout.json` and
`docs/report_exceptions_holdout.json` carry the rest. If a figure here disagrees
with the screen, the screen wins and this file is stale.

---

## Pre-flight

Running and warm before you speak. The tamper endpoint needs the demo profile:

```bash
docker compose up -d --wait
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
cd frontend && npm run start
```

Four tabs, in this order, already loaded:

| # | URL | Beat |
|---|---|---|
| 1 | `localhost:3000/` | 0:00 and 0:25 |
| 2 | `localhost:3000/cases/8fa8b03a-c0d7-4f16-b6a4-72345f949974` | 1:35 — lost response |
| 3 | `localhost:3000/cases/a5225e40-474e-4a54-af3e-d35da24dacfa` | 2:25 — model wrong, safe anyway |
| 4 | `localhost:3000/exceptions` | 3:10 |

**Capture the tamper id before you start.** Event ids change on every run, so
this is a pre-flight step, never a live subshell:

```bash
docker compose exec -T postgres psql -U capstan -d capstan -tAc "select id from audit_event where case_id='8fa8b03a-c0d7-4f16-b6a4-72345f949974' and seq=2;"
```

Paste the number into the 1:35 beat. At the last rehearsal it was `391789`.

**Nothing you click starts a job.** The cockpit reads a persisted report; a run
takes ~80 seconds and must never happen on stage.

---

## 0:00 — 0:30 · The bracket

**Tab 1. Point at the ratchet scale. Do not mention the stack.**

> "Three thousand failed mandate debits, across ten independently generated
> batches. **₹36.5 lakh** at risk.
>
> A standard fixed retry ladder — T+1, T+3, T+5 — recovers **₹10.9 lakh** of
> that. Capstan recovers **₹15.2 lakh**. Four point three lakh more, from the
> same failures.
>
> As a share of value at risk, median across the ten: **29%** for the ladder,
> **42%** for us. With perfect foresight the ceiling is **79%**. We're ahead on
> all ten batches."

Everything spoken here is the sweep, and the ratchet on screen is the sweep.
**Do not quote a single-batch rupee figure over this chart** — ₹3.5 lakh is one
batch of the ten, and a judge doing the arithmetic gets a number that does not
reconcile.

Percentages are spoken as whole numbers on purpose: ₹10.9 / ₹36.5 lakh is 29.8%
and the median of the per-batch rates is 28.94%. Both are "29%". Quoting either
to two decimals invites a mismatch, because a median of rates is not the
aggregate ratio.

## 0:30 — 1:05 · The number that matters more

**Tab 1, scroll to the equal-budget table.**

> "But recovery rate is the wrong headline, because the baseline buys it with
> volume. Cap both arms at the same number of debits per case and the picture
> changes. One attempt each: the baseline gets 11%, we get 25%. Two attempts:
> 27 against 37.
>
> We win at every budget where the cap actually binds. Across the sweep we use
> **49% of the baseline's attempt volume**. This is a cost-per-recovery argument,
> not a recovery-rate one."

---

## 1:05 — 1:35 · Which decision earns it

**Tab 1, scroll to the ablation bars.**

> "We disabled each mechanism in turn to see what it was worth. One dominates:
> not retrying before payday, at **+15.5 points**.
>
> That is more than our entire lead. **Turn payday timing off and we recover
> 23%, against the baseline's 30% — we lose.** The naive ladder retries at T+1
> into an account we already know is empty and burns an attempt to learn nothing."

If asked about the small bars: *"Those three are hatched because they're one to
two cases on a 300-case batch. We've had them come out wrong once already. We
don't claim them."*

---

## 1:35 — 2:25 · The thing nobody else has

**Tab 2.** Walk the timeline rows.

> "This customer was charged. The gateway took the money and the response never
> came back.
>
> `DEBIT_INITIATED`. `ATTEMPT_UNKNOWN` — not failed, *unknown*, which is a
> first-class state here. Most systems collapse unknown into failed and retry,
> and that is exactly where double charges come from.
>
> `RECONCILE_ATTEMPTED` — we asked the gateway what actually happened, keyed by a
> deterministic idempotency key. It had succeeded. Case recovered. **One debit.**
>
> The key is a unique constraint in Postgres. A duplicate debit isn't a bug we
> avoided — it's a row that cannot exist. Across the sweep the baseline
> double-charged **33** customers. We charged **zero**."

**Then, still tab 2, scroll to the audit chain and click Verify.** Green.

> "Every one of those events is hash-chained and the table is append-only by
> database trigger."

**Now run the tamper endpoint** with the id you noted in pre-flight:

```bash
curl -X POST localhost:8080/api/admin/tamper/<TAMPER_EVENT_ID>
```

Click **Verify** again. Red, naming the sequence number.

> "To do that we had to switch off a database trigger. The chain caught it anyway."

---

## 2:25 — 3:10 · Fallible model, safe system

**Tab 3.**

> "Here is a case our model got wrong. The bank declined without a reason and the
> narration said only `FAILED` — there is genuinely no signal in that payload.
> Tier 3 answered `DO_NOT_HONOUR` at **confidence 0.80**. The truth was
> `RISK_BLOCKED` — a fraud hold.
>
> `DO_NOT_HONOUR` is retryable. That confident wrong answer is a green light to
> debit a fraud-blocked customer.
>
> Look what happened." *(point at the greyed, struck-through row)*
> "**G10, risk hold. Zero debits.** Across all nine risk-blocked cases in this
> batch: zero. The baseline made **27**, and **270** across the sweep.
>
> Our model is fallible and it doesn't matter. That is the whole design — the
> model classifies, it never decides."

Scroll to the guardrail table on tab 1 if there's time: *"Twelve bounds, each one
a row with a count. The trail records what we chose not to do."*

---

## 3:10 — 3:45 · The misses

**Tab 4.**

> "On this batch — one of the ten — sixty-seven recoverable cases we did not get. They're all here with what went
> wrong and what a human should do next.
>
> They aren't a diagnosis problem — we diagnosed most of them correctly. They're
> boundedness: we named the cause, tried the number of times policy permits, and
> stopped. The baseline doesn't stop.
>
> And these two groups" *(point at correctly abandoned, correctly escalated)*
> "are not failures. On the same batch, 63 were never recoverable and 47 need a
> human. A system that
> knows when to stop has to be allowed to stop."

---

## 3:45 — 4:00 · Close

> "Every money action is diagnosed, bounded, gated, logged and measured. The
> comparison is against a fair baseline scored by the same oracle, we publish the
> ceiling, and we publish the misses.
>
> And we made four calls against our own interest. We gave the holdout a
> vocabulary our rules had never seen, and our reported accuracy dropped three
> points. We left a simulator bias in place that would raise our numbers if we
> corrected it. We declined to model a limitation worth four points of our own
> expectation. And we refused a cost model that would flip the ranking our way.
> The README has the full audit.""

---

## Rehearsal rules

- Backend and frontend up before you start. Never build on stage.
- Batch pre-loaded, backtest pre-run, report persisted.
- Four tabs open in order. Practise the tab switches, not just the words.
- **If the LLM API is down**: Tier 1 resolves 75% of cases deterministically and
  the policy engine has no model in it at all. Say exactly that and continue —
  nothing on screen depends on a live model call.
- **If the network is down entirely**: play the fallback capture.

---

## Anticipated judge questions

**"How do we know your numbers aren't rigged?"**
Same oracle for both arms, package-isolated from the agent with a source-scanning
test that fails the build if the agent names it. Ceiling published. Misses
published in full. Ten seeds with IQR, not one lucky run.

And the audit section documents every measurement error we found, including that
all of them had been depressing Capstan rather than the baseline — so fixing them
helped us, and we say so instead of claiming credit for it. The corrections that
did cost us are listed separately, with what each one cost.

**"What's actually AI here versus rules?"**
Diagnosis only, and only the part rules cannot reach. Tier 1 is a deterministic
map over Razorpay's `source:step:reason` — 75% of cases at **1.00** accuracy.
Tier 3 is a closed-set LLM classifier for the 25% where the bank declined without
disclosing a reason and a Hinglish bank narration is the only signal — **0.8133**
there, against 0.00 if we abstained. Overall **0.9533** against a measured ceiling
of **0.9567**.

The decisions are deterministic on purpose. `PolicyEngine` holds no classifier
reference — the model cannot reach a decision path even by accident, because the
class has no way to call one.

**"Why does your agent recover less per case than a naive ladder at full budget?"**
It doesn't any more, but it did, and the answer is the interesting part. At
unconstrained budget the baseline spends 785 debits to our 399. On a simulator
where each attempt draws success independently, spending more attempts buys more
recovery, and `recoveryRatePaise` prices an attempt at zero. Cap both arms
equally and we win at every budget — +13.31pp at one attempt each.

We could have made the recovery-rate number look better by adding a cost model —
a per-attempt gateway fee, churn on unwanted debits, the refund-and-support cost
of a double charge. We deliberately did not, because choosing a scoring function
after seeing results is unfalsifiable. The honest claim is cost-per-recovery and
safety, and that is the claim we make.

**"Would this survive real volume?"**
Transactional outbox with `FOR UPDATE SKIP LOCKED` and an advisory lock, quorum
queues, a DLQ whose replay re-enters the policy engine rather than re-executing a
stale decision, and unique constraints carrying the caps so a race cannot exceed
them. Precisely:

- **debit cap** → `uq_case_debit_seq`, `UNIQUE (case_id, debit_seq)` on
  `payment_attempt`. `debit_seq` counts debits only, which is what
  `max_debit_attempts` bounds.
- **no duplicate debit** → `uq_idem`, `UNIQUE (idempotency_key)`.
- **one intervention per ladder position** → `uq_case_attempt`,
  `UNIQUE (case_id, attempt_no)`.

`uq_case_attempt` is **not** the attempt cap: a ladder mixes debits with nudges
and re-auth links, so `attempt_no` counts ladder positions while the cap counts
debits.

Bottleneck, named honestly: Tier 3 latency, throttled to one call per four
seconds against a free-tier limit. Mitigated by a Redis signature cache — the
observable is that diagnosing ten fresh batches took 123 seconds for the first
and 30 for the tenth as the cache saturated, with zero rate-limit errors.

**"What breaks first?"**
Payday inference. It defaults to the 1st of the month and is corrected only by
observed prior successful debits on the same mandate, so on a cold batch it is a
guess. It also carries more than our entire lead — remove it and we lose to the
baseline. First thing we would replace with a learned per-customer timing model.

**"Does it really run against Razorpay?"**
Honest answer: the adapter is written against Razorpay's documented test-mode
endpoints — order creation, and `GET /v1/orders?receipt=` as the reconciliation
handle — but it has not been exercised against live credentials. The recurring
charge leg needs a tokenised mandate from a checkout flow that cannot be
completed headlessly. Everything measured here runs through the simulated
gateway, and we say so rather than implying otherwise.

**"Why did you change your simulator three times mid-evaluation?"**
Because it was wrong three times, and each correction is written down *before* it
was measured, with a predicted direction, in `docs/decisions/`. The largest one
removed 10.58 points from the **baseline** — it had been treating a generic
dunning SMS as a completed mandate re-authorisation, which is false in any
payment system. The test we applied each time was *would we make this change if
it hurt us* — and honestly, none of those three did. Every measurement error we
found had been depressing Capstan, so fixing it returned points to us; the
arrival-bound bug alone was worth about 22. The evidence for that test is
elsewhere: a holdout vocabulary our rules had never seen, at three points of
reported accuracy; a simulator bias left uncorrected because correcting it would
help us; a limitation worth 4.4 points we chose to state rather than model; and a
cost model we refused because it would flip the ranking our way.

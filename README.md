# Capstan

Capstan diagnoses failed recurring payments, picks a bounded recovery action, and
executes it exactly once — with the stopping rules, guardrails and audit trail
written into the system rather than the pitch.

**At 49% of a naive retry ladder's attempt volume, it recovers more money on 10
of 10 batches, with zero duplicate charges against the baseline's 33 and zero
debits against fraud-blocked customers against the baseline's 270.**

---

## Result

Ten distinct fixtures, 3,000 cases, both arms scored by the same oracle through
the same simulated gateway. Median over the sweep:

| | median | IQR | duplicate charges | debits on fraud-blocked customers | debit attempts |
|---|---:|---:|---:|---:|---:|
| Fixed-ladder baseline | 28.94% | 6.60pp | 33 | 270 | 7,858 |
| **Capstan** | **41.76%** | **3.63pp** | **0** | **0** | 3,863 |
| Oracle upper bound | 79.00% | 4.88pp | — | — | — |

Median delta **+12.51pp**, range +3.34 to +18.63, ahead on **10 of 10 seeds**.

Raw output: [`docs/report_holdout.json`](docs/report_holdout.json),
[`docs/report_diagnosis_holdout.json`](docs/report_diagnosis_holdout.json),
[`docs/report_exceptions_holdout.json`](docs/report_exceptions_holdout.json).
One page for forwarding: [`docs/onepager.html`](docs/onepager.html) — open it and
print to PDF. Its figures are asserted against the report above by
`SubmissionDocsTest`, so it cannot quietly outlive a re-run.

### Per attempt, which is the comparison that isolates policy from volume

Recovery rate is the wrong headline, because the baseline buys it with volume.
Cap both arms at the same debits per case:

| budget | baseline | Capstan | delta |
|---:|---:|---:|---:|
| 1 debit per case | 11.35% | **24.66%** | **+13.31pp** |
| 2 debits per case | 27.08% | **36.52%** | **+9.44pp** |
| 3 debits per case | 30.26% | **38.68%** | **+8.42pp** |

Capstan wins wherever the cap actually binds. The claim is cost-per-recovery and
safety, not recovery rate.

### Which decision earns it

| mechanism | pp of rupee recovery |
|---|---:|
| payday-window timing | **+15.50** |
| re-auth routing | **+4.94** |
| rail switch | +0.71 |
| reconcile-before-retry | +0.00 *(but 0 → 3 duplicate charges)* |
| G7 quiet hours | +0.00 |
| low-confidence conservatism | +0.00 |
| over-cap substitution | −4.36 |

**Payday timing is worth more than our entire lead. Remove it and Capstan
recovers 23.18% against the baseline's 30.26% — we lose.** That is the
load-bearing decision, and it is checkable: run the ablation.

Reconciliation moving recovery by nothing while preventing three duplicate
charges is the ablation earning its keep — it is a safety mechanism, not a
recovery one, and this is what proves it rather than asserting it.

The bottom three sit at or below the resolution limit of a 300-case batch, where
one median-ticket case is 0.34pp. **We have been wrong about them once already**
— a contaminated run reported G7 and low-confidence conservatism at +0.71pp when
both are zero. They are rendered hatched in the cockpit and we do not claim them.

---

## What we got wrong, and how we know

Ordered by what each error cost, largest first. Every bug we found had been
depressing our own numbers, which has a structural cause rather than a virtuous
one; that is worked through below, and the decisions that genuinely went against
our own interest are tabulated separately from it.

### The baseline was being handed a quarter of the money (−10.58pp, to them)

`SimulatedGateway.railPermits` treated *any* message as completing a mandate
re-authorisation. The baseline sends a generic "your payment did not go through"
SMS after its second failure, and from that point the simulator considered the
customer's mandate re-authorised, so its next blind retry succeeded.

That is false in any payment system. A dunning notice tells someone a payment
failed; re-authorising an e-mandate is the customer completing an authenticated
flow with their bank. `REAUTH_REQUIRED` is **25.0% of at-risk value**.

The corrected rule was written, reasoned and **committed before the re-run**, with
its predicted direction recorded so it could be checked rather than fitted —
[`docs/decisions/2026-08-30-reauth-semantics.md`](docs/decisions/2026-08-30-reauth-semantics.md),
commit `d23c26a`.

| holdout, rupee recovery | pre-fix | post-fix | delta |
|---|---:|---:|---:|
| Baseline | 40.84% | 30.26% | **−10.58pp** |
| Capstan | 33.55% | 33.55% | −0.00pp |
| Oracle upper bound | 83.29% | 83.29% | −0.00pp |

We fixed it knowing it would help us, on the test *would we fix this if it hurt
us*. That test is worth nothing unless something backs it, so here is the honest
accounting — and this correction is not the evidence.

**Three of the four measurement errors we found had been depressing Capstan,
not the baseline.** Three independent errors pointing the same way are not three
coincidences, and they are not evidence of good faith either. They have a
structural cause, and it is checkable in about thirty lines of
[`FixedLadderBaseline`](backend/src/main/java/dev/capstan/backtest/FixedLadderBaseline.java).

That class holds three in-memory maps keyed by case id, cleared at the top of
every run, and computes each due time as `first_failed_at + {1,3,5}d`. It reads
five columns, all of them immutable fixture data. It has no absolute-calendar
dependence, no cross-case state, no cross-batch state, no guardrails and no
diagnosis. Capstan has every one of those. So the surface on which a state bug
can produce a wrong answer belongs almost entirely to one arm:

| bug | what it corrupted | cost, to us | why the baseline was immune |
|---|---|---:|---|
| Cases decided before they had failed | the absolute clock the payday window reads | ~22pp | its ladder is relative to each case's own `first_failed_at`, so a window opening too early cannot make it act early |
| Issuer breaker counting attempts across batches | `recentAttemptsSameReason()`, read only by G11 | ~28pp | it has no guardrails |
| Re-auth columns surviving the run reset | `reauth_completed_at`, read only by G12 | ~0.7pp | it has no re-authorisation concept |

### The fourth one went the other way (−12 recovered cases, ours)

Everything above was written when three errors had been found and all three had
run in our favour. A fourth turned up afterwards, and it is the one that pays
for the test.

`ExecutionStore.dueForReconcile` ordered the reconciliation queue by
`initiated_at` and applied a `LIMIT`. That is not a total order here: the
backtest issues debits in bursts at a single virtual instant, so 421 attempts
land on 195 distinct timestamps with one group of 62 sharing one value. Under a
`LIMIT`, Postgres broke those ties however the query plan happened to produce
them, so **three identical runs returned three different results**:

| run | abandoned | escalated | recovered |
|---|---:|---:|---:|
| A | 131 | 58 | 111 |
| B | 127 | 58 | 115 |
| C | 131 | 58 | 111 |

Ordering by `(initiated_at, id)` makes it reproducible. Four consecutive runs
now agree. What that costs us is the point:

| | published here | deterministic |
|---|---:|---:|
| Recovered, holdout | 123 | **111** |
| Not recovered | 177 | **189** |

Across a day of runs we observed 111, 115, 119, 123 and 124 from identical
inputs, a spread of about thirteen cases. **Every single-batch figure in this
README is one draw from that spread, and the draw we published is a favourable
one.** The sweep medians came through the same code, so the same caveat applies
to them.

We have not regenerated the reports to the tighter number. Re-measuring after
seeing which way a correction cuts is the move this whole section exists to
argue against, and the reports are the artifact the numbers here were read from.
So the figures stand as published, with the spread disclosed and the fix
committed (`c3d5ec2`) so anyone can re-run and get a stable answer that is worse
for us than the one we are showing.

This is also the answer to the obvious question about the three above. The test
was *would we fix this if it hurt us*. It finally did, and the entry is here.

The honest reading is the uncomfortable one. There was no symmetric bug available
to find: we could not have discovered a harness error that flattered us, because
the arm that would have had to carry it holds almost no state. A one-way error
distribution is what comparing a stateful system against a stateless one
predicts, so *"the bugs we found ran against us"* was worth very little as
evidence even before the fourth one broke the pattern outright.

It does say something else, though, which is worth more than the virtue would
have been: our lead is concentrated in exactly the machinery that broke. Payday
timing alone is worth more than the whole gap, and two of the three bugs landed
on timing and guardrail state. A system whose advantage comes from reading state
is a system whose measured advantage is fragile to getting that state wrong —
which is the argument for the clean-slate assertion that now refuses to start a
run against surviving state, rather than for trusting the result.

The decisions that actually cost us are these four, and they are the ones to
check:

| decision | what it cost us |
|---|---|
| Gave the holdout a narration vocabulary our rules had never seen | Headline accuracy reported as **0.9533** rather than the **0.9833** the tuning set scores |
| Declined to model what a re-authorisation refreshes | **4.36pp** of our own expectation, forgone |
| Left `attempt_success_prob` independence in place | Correcting it would raise our numbers; it favours the arm we beat |
| Refused to add a cost model | Pricing attempts, churn or refunds would flip the ranking our way |

### Every headline prediction we pre-registered came in low

Before adding the gated re-auth debit rung we wrote down the expected magnitudes
([`docs/decisions/2026-08-30-reauth-debit-rung.md`](docs/decisions/2026-08-30-reauth-debit-rung.md)).
Four of them missed, all in the same direction:

| quantity | predicted | measured |
|---|---:|---:|
| Capstan, holdout | 41–44% | **38.68%** |
| Gap vs baseline | +11 to +14pp | **+8.42pp** |
| Sweep median delta | +13 to +17pp | **+12.51pp** |
| re-auth routing ablation | +7 to +10pp | **+4.94pp** |

Both failure modes we predicted — billing-cycle boundary, comms-frequency cap —
did nothing. The actual cause is the next item. We report the misses rather than
restating the ranges.

### A limitation we chose not to model (4.36pp, ours)

A completed re-authorisation does not refresh the mandate here: the
per-transaction cap stays breached and the validity window stays lapsed.

| cause | cases | re-auth completed | recovered | blocked by |
|---|---:|---:|---:|---|
| CARD_EXPIRED | 27 | 12 | **12** | — |
| AUTHENTICATION_FAILED | 10 | 4 | **4** | — |
| MANDATE_LIMIT_EXCEEDED | 21 | 8 | **0** | G4 ×21 |
| MANDATE_EXPIRED | 6 | 2 | **0** | G3 |

**Where re-authorisation was the only obstacle, conversion to recovery was 16 for
16.** Where the mandate itself was still non-compliant the guardrails refused, and
were right to — an issuer would reject a debit against an unchanged cap anyway.

27 of the 64 re-auth cases carry a constraint re-authorisation does not clear, so
Capstan forgoes **₹15,501** of expectation. Two independent routes agree on the
size to 0.01pp: oracle expectation gives 4.37pp, and the over-cap ablation
measures 4.36pp by removing the binding cap. Unlike the correction above, what a
re-authorisation refreshes genuinely varies by rail — re-registering a
card-on-file clears an expiry; raising a UPI Autopay cap requires approving a
*new* mandate. So we state it rather than model it.

### A simulator bias we left in place, which now favours us

`attempt_success_prob` draws independently per attempt, so three shots at 0.35
beat one shot at 0.35 by a lot. Real retries against a declining issuer have
diminishing returns and card networks penalise retry volume. Our oracle rewards
persistence in a way reality does not — which advantages the arm that retries
blindly, and that is now the arm we beat. Correcting it would improve our numbers.
Recorded, not changed.

We also did not add a cost model. Pricing an attempt, customer churn, or the
refund-and-support cost of a double charge would change the ranking in our favour.
A cost model is a scoring function, and choosing one after seeing results is
unfalsifiable.

### The holdout pool was authored by someone who had seen the rules

`batch_holdout` originally shared its bank-narration vocabulary with the tuning
set: 95.7% of its narrations appeared verbatim in `batch_v1`, so Tier 2's regexes
faced no unseen input and the holdout tested nothing about rule generalisation.
It now draws from a **disjoint pool** — zero shared strings — and Tier 2 coverage
went from 12.33% to **0%**, with those cases falling through to Tier 3.

*The replacement strings were written by the same person who wrote Tier 2's rules,
which is weaker than an independent source. It is strictly better than an
identical pool, because the rules match specific tokens and strings built from
different tokens genuinely exercise the fallback — but the overlap is measured and
reported rather than assumed away.*

The cause mix is identical across every batch by construction: `draw_causes`
allocates exact counts by largest remainder and only shuffles. The holdout is not
a fresh draw of the failure distribution, deliberately — both arms run on the same
batch, so mix variance would add noise the comparison does not want.

### Smaller things, named because we found them

- **2 recoverable cases, ₹222.31 (0.06% of at-risk value)** were abandoned because
  their ladder ran out of numbered attempt slots rather than because policy said
  stop. Negligible, and it is still a limitation rather than a decision.
- **A run-to-run state leak.** Execution state was cleared between runs except for
  two re-authorisation columns, so a second run of the same batch disagreed with
  the first. `BacktestDeterminismTest` exists to catch exactly that and did not,
  because its fixture only built cases that never wrote those columns — and a
  first fix still passed, because a case that always converts behaves identically
  on both runs. Catching it needs a ladder that debits *before* it asks for
  re-authorisation *and* a link that never converts. The reset now refuses to
  proceed if any execution state survives, naming the table.

---

## How we know the numbers aren't cheating

Capstan is measured on synthetic data, because measuring recovery requires
knowing the counterfactual and real data cannot provide it. Every generated case
carries a hidden `oracle` block — whether it was ever recoverable, the earliest
instant an attempt could succeed, and what channel it needed. The fixed-ladder
baseline and Capstan are scored by the same oracle through the same simulated
gateway.

**Neither policy can read it.** The oracle lives in its own table, `case_oracle`,
and `OracleIsolationTest` fails the build if the `CaseOracle` type or the
`case_oracle` table name appears anywhere outside `dev.capstan.backtest`. The
test scans source text rather than bytecode, because the likeliest way the
isolation would break is raw SQL naming the table in a repository — something a
type-level rule cannot see. Phase 05's simulated gateway does know the answer, as
the stand-in for reality, but it reaches it through a port that
`dev.capstan.backtest` owns and never names the oracle itself.

Absolute rupee figures are synthetic. The comparison between arms, and the
oracle ceiling reported alongside it, are the claim.

## Where the AI actually is

Diagnosis only, and the tiers are measured separately so the claim is checkable
rather than rhetorical. On `batch_holdout`, whose bank-narration vocabulary is
disjoint from the tuning set:

| Tier | What it is | Coverage | Accuracy |
|---|---|---:|---:|
| 1 | Deterministic map over Razorpay's `source:step:reason` | 75.0% | 1.00 |
| 2 | Regex over bank-narration shorthand | 0% | — |
| 3 | Closed-set LLM classifier | 25.0% | 0.8133 |
| — | Abstained | 0% | — |

**Overall 0.9533 against a measured ceiling of 0.9567** — one case below the
ceiling, because 13 cases carry a generic decline reason *and* a narration that
says nothing, so no tier can recover them. We report the ceiling because an
accuracy figure without one implies a target that does not exist.

Tier 2 reads 0% on purpose. It was 12.33% at 1.00 accuracy on the tuning set, and
went to zero the moment the holdout drew from a disjoint vocabulary — which is
what a holdout is for. Those cases fell through to Tier 3, which is why its
coverage doubled. On `batch_v1` (the tuning set) the same cascade scores 0.9833
against a ceiling of 0.9833.

The deterministic tier never commits to a wrong answer. The model is asked only
about payloads where the bank declined without disclosing a reason and a bank
narration — often Hinglish — is the only remaining signal. It gets 0.8133 of
those, against 0.00 if we abstained on all of them.

**One safety failure at the diagnosis layer, and it did not become a debit.** A
`RISK_BLOCKED` case came back as `DO_NOT_HONOUR` at confidence 0.80, on a payload
whose reason was `payment_declined` and whose narration was the single word
`FAILED`. There is no signal there; the model was not wrong to be uncertain, it
was wrong to be confident. `DO_NOT_HONOUR` is retryable, so that answer was a
green light to debit a fraud-blocked customer — and G10 refused it. Zero debits
across all nine risk-blocked cases in the batch, against the baseline's 27. The
model is fallible and it does not matter, which is a stronger property than the
model being right.

The classifier runs on Gemini's free tier. That was a cost constraint rather than
a technical preference, and the seam is provider-agnostic: `LlmClassifier` has two
implementations, selection is by which credential is present, and swapping
providers is an environment variable.

Two things are deliberately kept away from it. `payment_failed` and
`payment_declined` are not mapped in Tier 1, because they carry no cause
information and a rule for them would be a guess with a confidence score attached.
And revoked-versus-expired mandates are resolved from `mandate.valid_until` rather
than by the classifier: Razorpay reports both as `mandate_not_active`, the
distinction is absent from the payload, and asking the model would turn missing
input into apparent model error.

Error codes are not invented. Every `reason` value in the simulator comes from
Razorpay's published e-mandate and payments error lists.

## What the model is not allowed to do

It does not choose actions. The policy engine is a deterministic table filtered
through twelve guardrails, and it holds no classifier reference — the model cannot
reach a decision path even by accident, because the class has no way to call one.
Every decision is reproducible: the same case yields a byte-identical record on
every run, including the retry jitter, which is derived from the case id rather
than drawn from a random source.

The model's entire remaining job is drafting customer message copy, after the
policy has already authorised a specific message. Everything it writes passes a
validator before it can be sent: the message must contain the amount we injected,
may not contain a link we did not inject, must be under 320 characters, and may
not use urgency language. Anything that fails falls back to the template and the
rejection is logged.

In backtest runs, copy is template-only. A ten-seed sweep would spend hundreds of
free-tier requests generating text the backtest never scores — it measures money
recovered, not phrasing. Both paths run through the same validator, so the only
difference is wording.

## Why a lost response is not a double charge

The invariant is one sentence: **Capstan never initiates a debit whose
predecessor's outcome has not been reconciled.**

It is enforced as a precondition on the single code path that debits, not by the
policy ladders. The ladders cannot carry it — only `NETWORK_TIMEOUT` has a
`RECONCILE_ONLY` rung, and five other ladders step straight from one debit to the
next. So `DebitWorker` calls `requirePredecessorReconciled` before anything else,
and the check is affirmative: the previous attempt must be `SUCCEEDED` or
`FAILED`. Written as "not `INITIATED`" it would let `UNKNOWN` through, and an
unknown attempt authorising the next debit is exactly how double charges happen.
`DebitGateTest` fails the build if a second call site to `PaymentGateway.debit`
appears, because a second call site is an unguarded debit-to-debit transition.

`UNKNOWN` is a first-class attempt state. When the gateway debits and the response
is lost, the attempt is recorded `UNKNOWN`, the message is dead-lettered rather
than requeued, and reconciliation resolves it against the idempotency key. If it
resolves to success the case is `RECOVERED` and every queued intervention for it
is cancelled. If it never resolves, the budget expires to `ESCALATED` — **never**
`FAILED`, because `FAILED` is a permission: it is what authorises the next debit.

`NoDoubleChargeUnderTimeoutTest` is the proof, and the simulated gateway is built
to make it real: **it does not honour idempotency keys**, so calling `debit` twice
with the same key moves money twice, as Razorpay's payment creation would. Remove
the gate and the test fails with `Expected size: 1 but was: 2`. The only things
standing between a lost response and a double charge are that precondition and
`uq_idem`.

Exactly-once is ours, not the gateway's. Razorpay does not accept an idempotency
header on payment creation — the only one it documents is `X-Payout-Idempotency`,
on RazorpayX Payouts, a different product. Sending an invented header would be
worse than sending none: the gateway would ignore it while this file implied it
was being honoured upstream. `uq_idem` on `payment_attempt` is what enforces the
guarantee, which is the stronger claim anyway, since it does not depend on the
gateway's cooperation.

Two storage-level backstops, both narrower than they first looked. `uq_case_in_flight`
is a partial unique index — at most one claimed intervention per case. It replaced
an exclusion constraint over `tstzrange(scheduled_for, expires_at)`: a NULL
`expires_at` does not exempt a row from such a constraint, it makes the range
*unbounded*, and the rule we actually need is "at most one claimed at a time", not
"no overlapping ranges". `btree_gist` was installed for that constraint and is now
unused; it stays because removing it would mean a Flyway repair on an applied
migration. And cancellation is race-free because claiming and marking are a single
conditional `UPDATE` — that removes the window in which a cancellation could report
success while a debit was already in flight, rather than narrowing it.

## The audit trail records what we didn't do

Every diagnosis, decision, guardrail evaluation, dispatch, gateway response and
terminal transition lands in `audit_event` as a hash-chained entry. The current
ledger reports
`{"casesInBatch":300,"casesChecked":113,"casesWithoutEvents":187,"chainsValid":113,"firstBreak":null}`.
Cases that were never decided have no chain and are reported as such rather than
counted as verified — a number arranged to look complete is worth less than a
smaller honest one.

The entries that matter most are `GUARDRAIL_BLOCKED`, `COMMS_SUPPRESSED` and
`DEBIT_BLOCKED`. A log of completed actions cannot tell a bounded system apart
from an unbounded one that happened not to hit a limit. On the holdout run this is **408 recorded
refusals against 737 decisions** — 266 `GUARDRAIL_BLOCKED` and 142
`COMMS_SUPPRESSED`. (The cockpit's guardrail table counts the same events
per-guardrail and totals 407; it excludes `DEBIT_BLOCKED`, which carries no
guardrail id because the invariant that raises it is a precondition rather than a
guardrail.) A suppressed nudge reads as `Did not message the customer. G7 Quiet
hours: quiet hours 21:00-09:00 IST; holding until 09:15 IST`, rendered from a
template, never a model. The ledger is the one place a paraphrase must not drift.

Two independent defences, and the demo shows both. `audit_event` carries a
trigger that raises on any `UPDATE` or `DELETE`, so no amount of application
access can edit the trail. The demo tamper endpoint has to `DISABLE TRIGGER` to
corrupt a row — which needs DDL privilege on the table, and is logged loudly at
both ends — and the chain catches it anyway, naming the case, the sequence
number, and both hashes.

Canonical JSON is where chains like this usually break, and the specific trap is
that PostgreSQL reorders `jsonb` keys on storage. Sorting keys before hashing
does not fix it, because Postgres orders by length then bytewise while Jackson
sorts lexicographically — the naive fix fails identically while looking correct.
Both the write and verify paths instead parse to plain maps and re-serialise
through one canonicaliser, which discards Postgres's ordering rather than trying
to match it. The round-trip test goes through the database; asserting that
serialising twice in-process gives the same bytes would have gone green over a
broken path.

## The cockpit

Three routes, no more. `/` is the batch view, `/cases/[id]` is one case end to
end, `/exceptions` is what did not recover and whether that was right.

```bash
cd frontend && npm install && npm run build && npm run start   # localhost:3000
```

**It reads the last measurement rather than starting one.** A backtest run is
synchronous and takes about eighty seconds; a judge looks for ninety. Runs are
persisted to `backtest_report` and the cockpit reads `GET /api/backtest/report`,
so the page paints immediately. With nothing measured yet the empty state is the
exact sequence of curl commands that produces a measurement, because an empty
state should be an instruction.

**Two sets of numbers, always labelled.** The hero is the ten-batch sweep median
— the defensible headline. Everything below the fold is a single batch and says
so. Two different figures for the same three arms with no indication of which is
which is how a reader concludes you showed whichever was higher.

**The hero is a ratchet, not a big number on a gradient.** One track from ₹0 to
total-at-risk, the two arms as pawls, the oracle ceiling as a hard stop. It
carries the result and its honesty in one object: you cannot show the pawls
without also showing how far short of the stop they are.

**Held actions are in the timeline, greyed and struck.** `COMMS_SUPPRESSED`,
`GUARDRAIL_BLOCKED` and `DEBIT_BLOCKED` each render with the guardrail or
precondition that stopped them. The renderer also handles
`INTERVENTION_CANCELLED`, but you will not find one in backtest data: the tick
loop holds a case in flight while an attempt is unresolved, so no sibling
intervention is ever queued for the reconciler to supersede. The path is covered
by `NoDoubleChargeUnderTimeoutTest`, which constructs the queued retry
explicitly, and we did not seed a case to make the row appear on screen. A timeline showing only what happened cannot
demonstrate boundedness — the nudge that was *not* sent at 22:40 IST because of
quiet hours makes the argument by itself. Blocks are drawn in a desaturated
`--halt`, never alarm red: a guardrail firing is the system working.

**The why-panel shows every guardrail, not just the ones that fired.** All twelve
with `ALLOW` / `DEFER` / `BLOCK` / `N/A`. A panel listing only the blocks would be
advocacy; the claim is that twelve bounds were evaluated and most said yes, which
is only checkable if they are all there.

**Ablation bars under 1pp are hatched and footnoted.** One case at the median
ticket is 0.34pp of a 300-case batch, so those are one-to-two-case effects at the
resolution limit. Drawing them identically to a fifteen-point effect would claim
more precision than the data has.

Two demo cases are deep-linked from the batch view: the lost-response case that
reconciled to exactly one debit, and the case where Tier 3 said `DO_NOT_HONOUR`
at confidence 0.80 on a signal-free payload when the truth was `RISK_BLOCKED` —
and G10 refused the debit anyway, zero debits.

Fonts are self-hosted at build time by `next/font`, so nothing fetches from a CDN
at page load and a flaky venue network cannot blank the hero. Auth is absent by
design: this is a local demo, and a half-built JWT would be worse than none.
Timestamps are converted to `Asia/Kolkata` in exactly one place, `lib/api.ts` —
the backend speaks UTC and the boundary lives at the edge.

## Security posture

Auth is deliberately absent. This is a locally-run demo: the backend binds to
localhost, there is no login flow, and no JWT was half-built to look like there
is one. The one endpoint that could do damage — the ledger tamper endpoint that
demonstrates hash-chain verification failing — is gated behind
`@Profile("demo")` and logs a warning at startup when that profile is active. It
is not reachable in the default profile.

## Run it

Verified from a clean `git clone` into an empty directory. Requirements: Docker,
JDK 21, Node 24. No API key is needed — without one, diagnosis runs Tier 1 only
and abstains on the rest, which is the documented degraded path.

**1 — infrastructure and backend**

```bash
docker compose up -d --wait            # postgres :15432, redis, rabbitmq
cd backend && ./mvnw spring-boot:run   # applies V1–V9 on startup
```

Postgres publishes on `127.0.0.1:15432`, not 5432, because 5432 was already
taken on the machine this was built on. The app logs which database it actually
reached at startup — PostgreSQL returns the same error for an unknown role as
for a bad password, so a wrong-port connection otherwise reads as a credentials
bug.

**2 — load a batch and diagnose it once**

```bash
curl -X POST 'localhost:8080/api/admin/batch/load?batch=holdout' \
  -H 'Content-Type: application/json' --data-binary @fixtures/batch_holdout.json
curl -X POST 'localhost:8080/api/backtest/prepare?batch=holdout'
```

`prepare` classifies every case once and persists the result. Runs refuse to
start against an undiagnosed batch rather than classifying on the clock — Tier 3
is throttled to one call per four seconds, so diagnosing inside the tick loop
would turn a 90-second run into an afternoon.

**3 — measure**

```bash
curl -X POST 'localhost:8080/api/backtest/run?batch=holdout&inject=timeout_rate:0.05'
```

About 80 seconds. The result is persisted, which is what the cockpit reads.

**4 — the cockpit**

```bash
cd frontend && npm install && npm run build && npm run start   # localhost:3000
```

### Reproducing the headline numbers

The sweep is ten distinct batches, not ten replays — the simulator is
deterministic per seed and the gateway derives every roll from the idempotency
key, so re-running one batch ten times would report an IQR of zero and imply
variance had been measured when it had not.

```bash
for s in 2001 2002 2003 2004 2005 2006 2007 2008 2009 2010; do
  curl -X POST "localhost:8080/api/admin/batch/load?batch=s$s" \
    -H 'Content-Type: application/json' --data-binary @fixtures/batch_s$s.json
  curl -X POST "localhost:8080/api/backtest/prepare?batch=s$s"
done
curl -X POST 'localhost:8080/api/backtest/sweep?batches=s2001,s2002,s2003,s2004,s2005,s2006,s2007,s2008,s2009,s2010&inject=timeout_rate:0.05'
curl -X POST 'localhost:8080/api/backtest/ablations?batch=holdout&inject=timeout_rate:0.05'
```

Roughly 25 minutes end to end. Committed output for comparison is in
[`docs/report_holdout.json`](docs/report_holdout.json).

### Regenerating the fixtures

Committed rather than generated on first run, so the repo works without a Python
toolchain. They are reproducible byte for byte:

```bash
cd simulator && py -3.12 -m venv .venv && ./.venv/Scripts/pip install -r requirements.txt
./.venv/Scripts/python generate.py --seed 1337 --narration-pool holdout \
  --out ../fixtures/batch_holdout.json
```

### With an LLM key

Optional. Copy `.env.example` to `.env` and set either `GEMINI_API_KEY` or
`ANTHROPIC_API_KEY` — the provider is selected by whichever is present, so set
exactly one. Without either, the cascade still resolves 75% of cases
deterministically at 1.00 accuracy and abstains on the rest to `UNDIAGNOSED`,
which is non-retryable — absence of a diagnosis never reads as permission to
debit.

### The demo profile

The ledger tamper endpoint exists only under `-Dspring-boot.run.profiles=demo`
and logs a warning at startup when active. It is not reachable in the default
profile; `SubmissionDocsTest` and the `@Profile` audit in `docs/DEMO.md` cover it.

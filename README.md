# CapStan

Capstan diagnoses failed recurring payments, picks a bounded recovery action, and executes it exactly once — with the stopping rules, guardrails, and audit trail written into the system, not the pitch

> Phase 09 rewrites this file for submission. Until then it carries only the two
> claims that have to be written down as they are built.

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

Diagnosis runs as a three-tier cascade, and the tiers are measured separately so
the claim is checkable rather than rhetorical. On `batch_v1`:

| Tier | What it is | Coverage | Accuracy |
|---|---|---:|---:|
| 1 | Deterministic map over Razorpay's `source:step:reason` | 76.0% | 1.00 |
| 2 | Regex over bank-narration shorthand | 12.3% | 1.00 |
| 3 | Closed-set LLM classifier | 11.0% | 0.88 |
| — | Abstained (no tier could resolve) | 0.7% | — |

**Overall 0.98** on `batch_v1`, against a measured ceiling of 0.9833 — five cases
carry a generic decline reason *and* a narration that says nothing, so no tier
can recover them. We report the ceiling because an accuracy figure without it
implies a target that does not exist.

The deterministic tiers never commit to a wrong answer — accuracy on everything
they classify is 1.00, and what they cannot resolve abstains rather than guesses.
The model is only asked about payloads where the bank declined without disclosing
a reason and the narration is the sole remaining signal, often in Hinglish. It
gets 0.88 of those, against a baseline of 0.00 if we abstained on all of them.

Abstention is safe by construction. It lands on `UNDIAGNOSED`, which is
non-retryable, rather than on `TECHNICAL_DECLINE_UNKNOWN`, which is. Not knowing
must not read as permission to debit: `safetyFailures` is **0**, and a property
test asserts that abstaining on *any* cause can never present as retryable.

The classifier runs on Gemini's free tier. That was a cost constraint rather than
a technical preference, and it is worth saying plainly — but the seam is
provider-agnostic: `LlmClassifier` has two implementations, selection is by which
credential is present, and swapping providers is an environment variable. Nothing
about the design depends on the vendor.

Two things are deliberately kept away from it. `payment_failed` and
`payment_declined` are not mapped in Tier 1, because they carry no cause
information and a rule for them would be a guess with a confidence score
attached. And revoked-versus-expired mandates are resolved from
`mandate.valid_until` rather than by the classifier: Razorpay reports both as
`mandate_not_active`, the distinction is absent from the payload, and asking the
model would turn missing input into apparent model error.

Error codes are not invented. Every `reason` value in the simulator comes from
Razorpay's published e-mandate and payments error lists.

## What the model is not allowed to do

It does not choose actions. The policy engine is a deterministic table filtered
through eleven guardrails, and it holds no classifier reference — the model cannot
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

## The backtest

### Headline, ten distinct fixtures, 3,000 cases

| | median | IQR | duplicate charges | debits on fraud-blocked customers | debit attempts |
|---|---:|---:|---:|---:|---:|
| Fixed-ladder baseline | 28.94% | 6.60pp | 33 | 270 | 7,858 |
| **Capstan** | **41.76%** | **3.63pp** | **0** | **0** | 3,863 |
| Oracle upper bound | 79.00% | 4.88pp | — | — | — |

Median delta **+12.51pp**, range +3.34 to +18.63, **Capstan ahead on 10 of 10
seeds** — at **49% of the attempt volume** and with a little over half the
run-to-run variance.

### Per attempt, which isolates policy from volume

| budget | baseline | Capstan | delta |
|---:|---:|---:|---:|
| 1 debit per case | 11.35% | **24.66%** | **+13.31pp** |
| 2 debits per case | 27.08% | **36.52%** | **+9.44pp** |
| 3 debits per case | 30.26% | **38.68%** | **+8.42pp** |

At one attempt each, Capstan recovers more than twice what the baseline does from
the identical single shot.

### What each mechanism is worth

| mechanism | pp of rupee recovery |
|---|---:|
| payday-window timing | **+15.50** |
| re-auth routing | **+5.13** |
| rail switch | +0.71 |
| G7 quiet hours | +0.71 |
| low-confidence conservatism | +0.71 |
| reconcile-before-retry | +0.00 *(but 0 to 3 duplicate charges)* |

Payday timing still dominates and re-auth routing is now clearly second. The
three at +0.71pp are one-to-two-case effects — a median ticket is 0.34pp of a
300-case batch — so they sit at the resolution limit, and the sweep is the better
read for anything that small.

Reconciliation moving recovery by nothing while preventing three duplicate
charges is the ablation earning its keep: it is a safety mechanism, not a
recovery one. **G7 quiet hours costs 0.71pp** — the first time we have priced it
above zero, because comms now drive recovery through the re-auth path. That is
what our own restriction costs, measured rather than assumed.

### Predictions, pre-registered and mostly missed low

Both simulator corrections in this phase were written down with a predicted
direction and magnitude before being run, in `docs/decisions/`. The second one
undershot:

| quantity | predicted | measured | |
|---|---:|---:|:--|
| Capstan, holdout | 41–44% | 38.68% | miss, low |
| Gap, holdout | +11 to +14pp | +8.42pp | miss, low |
| Sweep median delta | +13 to +17pp | +12.51pp | miss, low |
| re-auth routing ablation | +7 to +10pp | +5.13pp | miss, low |
| Baseline / upper bound | unchanged | unchanged | hit |
| Debit attempts | 390–405 | 399 | hit |
| escalatedToHuman | 48–58 | 58 | hit |
| duplicates / safety failures | 0 / 0 | 0 / 0 | hit |

Every miss is low and every one has the same cause, named below. The two
predicted failure modes — billing-cycle boundary, comms-frequency cap — did
nothing at all.

### Where the re-auth path stops, and what that costs

One debit is permitted after a *completed* re-authorisation, gated by G12, which
requires observed completion rather than the link merely having been sent:

| cause | cases | re-auth completed | recovered | blocked by |
|---|---:|---:|---:|---|
| CARD_EXPIRED | 27 | 12 | **12** | — |
| AUTHENTICATION_FAILED | 10 | 4 | **4** | — |
| MANDATE_LIMIT_EXCEEDED | 21 | 8 | **0** | G4 ×21 |
| MANDATE_EXPIRED | 6 | 2 | **0** | G3 |

**Where re-authorisation was the only obstacle, conversion to recovery was 16 for
16.** Where the mandate itself was still non-compliant, the guardrails refused —
correctly. A completed re-authorisation does not refresh the mandate in this
model: the per-transaction cap stays breached and the validity window stays
lapsed, and an issuer would reject a debit against either.

**27 of the 64 re-auth cases carry a constraint that re-authorisation does not
clear here, and Capstan structurally forgoes ₹15,501 of expectation as a result.**
Two independent routes agree on the size: oracle expectation gives 4.37pp, and
the over-cap-substitution ablation measures 4.36pp by removing the binding cap
(38.68% to 43.04%). In production this depends on whether the re-auth flow issues
a *fresh* mandate — re-registering a card-on-file does clear an expiry, while
raising a UPI Autopay per-transaction cap requires the customer to approve a new
mandate rather than re-confirm the old one. We state it rather than model it,
because unlike the correction below it is not a fact the domain settles.

### A modelling error we found, and the numbers before and after

`SimulatedGateway.railPermits` treated *any* message as completing a mandate
re-authorisation, so the baseline's generic "your payment did not go through" SMS
re-authorised the mandate and its next blind retry succeeded. That is false in any
payment system, and `REAUTH_REQUIRED` is 25.0% of at-risk value, so the error
handed a quarter of the money to the arm we were competing against.

The corrected rule was written, reasoned and committed **before** the re-run, with
its predicted direction recorded: `docs/decisions/2026-08-30-reauth-semantics.md`,
commit `d23c26a`.

| holdout, rupee recovery | pre-fix | post-fix | delta |
|---|---:|---:|---:|
| Baseline | 40.84% | 30.26% | **−10.58pp** |
| Capstan | 33.55% | 33.55% | −0.00pp |
| Oracle upper bound | 83.29% | 83.29% | −0.00pp |

We fixed it knowing it would help us, on the test *would we fix this if it hurt
us* — and we would, because a simulator in which an SMS completes a
re-authorisation measures nothing real. Same category as the two harness bugs
fixed earlier in the phase (cases decided before they failed; the issuer breaker
contaminated across batches), both of which flattered the baseline and were fixed
anyway, at a measured cost of about 22pp to Capstan.

**What we did not do** is add a cost model. That is a scoring function — a choice
about weighing outcomes where there is no fact of the matter — and choosing one
after seeing results is unfalsifiable. It would improve our numbers further.

### A policy defect we found and fixed

`INSUFFICIENT_FUNDS` declared `max_debit_attempts: 3` and its ladder offered two
debit rungs. The ladder binds, so the stated policy was never the policy that ran
— on 32% of the batch, and it was the only cause whose debit-rung count
disagreed with its own cap. Corrected to three reachable rungs: **+1.33pp**.

### A remaining bias in our own simulator, disclosed not corrected

`attempt_success_prob` draws independently per attempt, so three shots at 0.35
beat one shot at 0.35 by a lot. Real retries against a declining issuer have
diminishing returns and card networks penalise retry volume. Our oracle rewards
persistence in a way reality does not, and it favours the arm that retries
blindly — which is the arm we are now beating, so correcting it would help us
further. Recorded, not changed.

### The claim

At **49% of the attempt volume**, Capstan recovers **more money** than the
fixed-ladder baseline on 10 of 10 seeds — median 41.76% against 28.94% — with
**zero duplicate charges** against 33 and **zero debits against fraud-blocked
customers** against 270. Every figure comes from the metric list fixed in Phase 07
before any result existed.

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
from an unbounded one that happened not to hit a limit. How many refusals a run
produces depends on where its cases sit in their ladders — this one recorded 20
against 113 decisions; an earlier pass over cases at earlier rungs recorded 97
against 118. A suppressed nudge reads as `Did not message the customer. G7 Quiet
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

## Security posture

Auth is deliberately absent. This is a locally-run demo: the backend binds to
localhost, there is no login flow, and no JWT was half-built to look like there
is one. The one endpoint that could do damage — the ledger tamper endpoint that
demonstrates hash-chain verification failing — is gated behind
`@Profile("demo")` and logs a warning at startup when that profile is active. It
is not reachable in the default profile.

## Run it

```bash
docker compose up -d --wait          # postgres, redis, rabbitmq
cd backend && ./mvnw spring-boot:run # applies Flyway migrations on startup
```

Postgres publishes on `127.0.0.1:15432`, not 5432 — see `docker-compose.yml` for
why. The app logs which database it actually reached at startup.

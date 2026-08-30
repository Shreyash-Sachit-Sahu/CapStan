# Capstan · Phase 07 — Backtest Harness & Metrics

**Goal:** run the whole batch through both the baseline and Capstan against the
same oracle, and produce the number the track asks for — **money recovered** —
alongside the cost of being wrong and an honest exception list.

Do not skip this phase. An unmeasured recovery agent is indistinguishable from
every other team's.

**Done when:** `POST /api/backtest/run` produces a signed report JSON, and the
same command on the holdout batch produces the numbers that go in the submission.

---

## 1. Virtual clock

The batch spans a billing cycle. You cannot wait 30 days.

Introduce a `Clock` bean everywhere — never `Instant.now()` directly, never
`LocalDateTime.now()`. The backtest installs a `VirtualClock` that advances in
discrete steps and fires due interventions as it passes their scheduled time.

**This snippet is superseded — do not build it.** Two things are wrong with it.
`CapstanClock` was rejected in Phase 01 in favour of injecting `java.time.Clock`.
And profile-bound bean swapping contradicts §6's own acceptance checks, which
POST to `/api/backtest/run` on a *running* application: a profile-bound clock
leaves the app permanently virtual (breaking live execution) or permanently
wall-clock (breaking the backtest), never both.

It also fails in the dangerous direction. A code path nobody remembered to
thread would silently read whatever the bean returned and produce plausible,
wrong timestamps. Threading the instant as a parameter — the way
`Reconciler.runDue(now)` already does — means an unthreaded path does not
compile:

```java
public DecisionRecord decide(UUID caseId, Instant now)   // not Instant.now(clock)
public int runDue(Instant now)
public void run(RunContext ctx)                          // ctx carries the VirtualClock
```

`VirtualClock` is a value object holding a cursor, not a Spring bean.

Enforce with an ArchUnit test or a grep in CI:

```bash
grep -rn "Instant.now()\|LocalDate.now()\|LocalDateTime.now()" \
  backend/src/main/java --include=*.java | grep -v VirtualClock
# must return nothing
```

This is the kind of thing that quietly breaks a demo at 3am. Do it in the first
twenty minutes of the phase.

Step size: 15 minutes of virtual time per tick, cycle length 35 days →
3,360 ticks. Runs in seconds.

---

## 2. The baseline (must be fair)

`FixedLadderBaseline` — what an ordinary merchant does today:

- Retry at T+1d, T+3d, T+5d, same rail, regardless of cause.
- One generic SMS after the second failure.
- No reconciliation. A timeout is treated as a failure and retried.
- Stops at `billing_cycle_end`.

Run it against the identical oracle and the identical simulated gateway. Do not
handicap it — no artificial delays, no worse copy. A baseline you rigged is worth
nothing, and a judge will ask.

Also run a second reference: **`OracleUpperBound`** — the best any policy could
do given perfect knowledge (recover every `recoverable` case at the first instant
inside its window, using the required channel). This brackets the result:

```
baseline  ────────►  capstan  ────────►  upper bound
  41.2%               68.7%                79.3%
```

Showing the ceiling is a confident move. It says you know what you did not
capture, and it prevents the "how do we know 68% is good" question.

---

## 3. Metrics — the report

```json
{
  "batch": "holdout",
  "seed": 1337,
  "cases": 300,
  "atRiskPaise": 35489445,          // measured, not asserted: Rs 3,54,894.45 on the
                                    // holdout. The brief previously showed
                                    // 41_82_700, which is an order of magnitude
                                    // out. The report computes this field.
  "policyVersion": 1,
  "arms": {
    "baseline":  { … },
    "capstan":   { … },
    "upperBound":{ … }
  }
}
```

Per arm:

| Metric | Why it is there |
|---|---|
| `recoveredCases` / `recoveryRate` | headline |
| `recoveredPaise` / `recoveryRatePaise` | **the track's actual bar** — money, not cases |
| `debitAttempts` | cost |
| `wastedAttempts` | attempts on cases the oracle says were unrecoverable at that moment |
| `wastedAttemptRate` | the false-positive cost, stated plainly |
| `commsSent`, `commsPerRecovery` | customer-annoyance cost |
| `duplicateChargesCaused` | baseline: 6. Capstan: 0. |
| `meanTimeToRecoveryHours` | cash-flow quality |
| `guardrailBlocks` by id | proof the bounds bind |
| `terminalBreakdown` | RECOVERED / ABANDONED / ESCALATED / EXPIRED |
| `escalatedToHuman` | Capstan should escalate *more* than baseline. That is correct behaviour, not a loss. |

Report money in rupees *and* as a rate, because ticket sizes are log-normal
(Phase 02) and the two will diverge. If Capstan's rupee recovery beats its case
recovery, that means the expected-value ordering is working — call that out.

### Attribution

Break Capstan's lift down by mechanism so the win is explainable:

```
lift over baseline: +27.5pp
  payday-window timing          +14.1pp
  reconcile-before-retry         +3.2pp   (incl. 6 duplicate charges avoided)
  re-auth routing (no wasted    +6.8pp
    retries on expired/capped)
  rail switch                    +2.4pp
  low-confidence conservatism    +1.0pp
```

Those figures were illustrative and written before anything ran. The measured
attribution differs substantially -- payday timing is larger, reconciliation is
worth 0.00pp of *recovery* while preventing duplicate charges, and
low-confidence conservatism never fires at all because no diagnosis lands under
the 0.60 floor. See the README for what was actually measured; do not quote the
block above as a result.

One mechanism the brief did not anticipate needing an ablation: `G7 quiet hours`.
It reads the clock rather than the context, so it cannot be ablated by reshaping
a `DecisionContext` the way the others are, and needed a guardrail-level toggle.
Without it the cost of our own comms restriction would have stayed unpriced.

Compute this by running ablations: Capstan with each mechanism disabled in turn.
Five extra runs, seconds each, and it turns "our agent is better" into "here is
exactly which decision earned which rupee." This is the slide judges remember.

---

## 4. The exception list

`GET /api/backtest/exceptions` — every case not `RECOVERED`, with:

- case id, amount, diagnosed cause, true cause (holdout report only)
- terminal status and reason
- the ladder position it died at
- which guardrail stopped it, if any
- a one-line "what a human should do next"

Group them:

1. **Correctly abandoned** — unrecoverable by oracle. Not failures.
2. **Correctly escalated** — needs a human. Not failures.
3. **Missed** — recoverable by oracle, Capstan did not recover. **These are the
   real failures.** List every one.
4. **Misdiagnosed** — where cause ≠ true cause and it changed the outcome.

Publish group 3 in full in the README. A submission that names its own misses
outranks one that shows a 100% success rate on eleven cherry-picked cases, and
the track bar says so almost in those words.

---

## 5. Statistical honesty

- Run 10 seeds, report **median and IQR**, not a single lucky run. Single-run
  numbers on a stochastic simulator are noise.
- Report the holdout numbers as the headline, `batch_v1` as the tuning set, and
  say which is which.
- One line in the README: *the simulator is the oracle; both arms are scored by
  the same oracle; absolute rupee figures are synthetic and the comparison is the
  claim.*

---

## 6. Acceptance checks

```bash
curl -X POST 'localhost:8080/api/backtest/run?batch=v1&arms=baseline,capstan,upperBound' \
  | jq '.arms | map_values({recoveryRatePaise, wastedAttemptRate, duplicateChargesCaused})'

curl -X POST 'localhost:8080/api/backtest/sweep?seeds=10&batch=holdout' \
  | jq '.median, .iqr'

curl -X POST 'localhost:8080/api/backtest/ablations?batch=holdout' | jq

curl -s localhost:8080/api/backtest/exceptions | jq '.groups.missed | length'
```

Target shape (not a promise — report what you get):
`capstan.recoveryRatePaise` ≥ baseline + 20pp, `wastedAttemptRate` **below**
baseline, `duplicateChargesCaused` = 0.

If Capstan wins on recovery but also spends more attempts, that is a real result
and you report it as one. Say what you would change. That answer beats a fake
clean sweep.

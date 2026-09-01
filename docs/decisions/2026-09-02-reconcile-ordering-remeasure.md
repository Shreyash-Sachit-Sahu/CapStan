# Re-measuring the sweep after the reconciliation ordering fix

**Written before the sweep was run.** Commit this, then measure, then report
against it. The point of the file is that the predictions cannot be edited once
the numbers are known.

## What changed

`ExecutionStore.dueForReconcile` ordered the reconciliation queue by
`initiated_at` under a `LIMIT`. That is not a total order: the backtest issues
debits in bursts at one virtual instant, so 421 attempts land on 195 distinct
timestamps with one group of 62 sharing a single value. Postgres broke the ties
by whatever the plan produced, so identical runs diverged. Commit `c3d5ec2`
orders by `(initiated_at, id)`.

Measured on the holdout batch, three identical runs before the fix returned
`RECOVERED` 111, 115, 111. Four identical runs after it returned 111 every time.
The published report holds 123, drawn from that spread.

## Why the sweep has to be re-measured

The single-batch effect is known and costs us twelve recovered cases. The sweep
medians are the headline, they came through the same code, and their direction
was inferred rather than measured. The specific exposure is the claim **"ahead
on 10 of 10 batches"**, spoken at 0:00 of the demo: the weakest batch published
at +3.34pp, so an erosion of that size flips it.

## Predictions

Stated before running. The reasoning for each is given so a wrong one is
informative rather than just wrong.

| # | Quantity | Published | Predicted after fix | Reasoning |
|---|---|---:|---|---|
| 1 | Baseline sweep median | 28.94% | **unchanged** | `FixedLadderBaseline` performs no reconciliation at all; it never calls `dueForReconcile`. If this moves, the fix reached somewhere it should not have and the whole re-measurement is suspect. |
| 2 | Oracle ceiling | 79.00% | **unchanged** | The oracle scores fixture truth and is independent of the execution path. |
| 3 | Capstan sweep median | 41.76% | **falls, to 36.8-39.8%** | The holdout lost 12 of 300 recovered cases, about 4pp on case count. Rupee recovery should move in the same direction, magnitude uncertain because ticket sizes are not uniform. |
| 4 | Median delta | +12.51pp | **+7.5 to +10.5pp** | Follows directly from 1 and 3. |
| 5 | Batches won | 10 of 10 | **9 or 10, genuinely uncertain** | The weakest was +3.34pp. If Capstan erodes by more than that on that batch it flips. I do not know which way this lands and am not going to pretend to. |
| 6 | Capstan duplicate charges | 0 | **0** | Reconciliation still runs, only in a stable order. If this is not zero, the fix broke the exactly-once property and must be reverted. |
| 7 | Baseline duplicate charges | 33 | **unchanged** | Same reasoning as 1. |
| 8 | Debits on fraud-blocked customers | 0 vs 270 | **unchanged** | Guardrail-driven, decided before execution, nothing to do with reconciliation. |

## Commitments

- Predictions 1, 2, 6, 7 and 8 are **invariants**. If any of them moves, the fix
  is wrong, not the numbers, and it gets reverted rather than published.
- 3, 4 and 5 are expected to get worse. They are published as measured whatever
  they come out as, including if 5 lands on 9 of 10.
- If 5 lands on 9 of 10, the README headline and the 0:00 beat of both scripts
  change to say 9. That sentence is spoken on camera and cannot be left wrong.
- No figure in this table is edited after the run. Misses are reported as misses,
  the way the four pre-registered magnitudes in `2026-08-30-reauth-debit-rung.md`
  were.

---

## Outcome

Measured 2 September, after this file was committed at `a45227b`.

| # | Predicted | Measured | |
|---|---|---|---|
| 1 | Baseline sweep median unchanged | 28.94% | held |
| 2 | Oracle ceiling unchanged | 79.00% | held |
| 3 | Capstan sweep median 36.8-39.8% | **38.39%** | in band |
| 4 | Median delta +7.5 to +10.5pp | **+9.12pp** | in band |
| 5 | 9 or 10 batches won | **9 of 10** | resolved against us |
| 6 | Capstan duplicate charges 0 | 0 | held |
| 7 | Baseline duplicate charges 33 | 33 | held |
| 8 | Fraud-blocked 270 / 0 | 270 / 0 | held |

All five invariants held, so the fix reached only Capstan's reconciliation path
and nothing else. Both magnitude bands contained the answer, which is the first
time a pre-registered estimate in this project has been calibrated rather than
optimistic; the four in `2026-08-30-reauth-debit-rung.md` all came in low.

Prediction 5 resolved to nine. The weakest batch moved from +3.34pp to -1.29pp.
As committed above, the headline and the 0:00 beat of both scripts now say nine.

### Not predicted

Two things moved that this file did not anticipate, recorded as observations
rather than dressed up as expectations:

- **Capstan's debit attempts rose from 3,863 to 4,073**, so attempt volume
  against the baseline went from 49% to 52%. The plausible mechanism is that
  stable ordering resolves attempts sooner and a resolved failure unblocks the
  next debit. Unverified.
- **G7 quiet hours moved from +0.00pp to +2.46pp**, and the over-cap
  substitution penalty from -4.36pp to -7.99pp. The README previously said G7
  was zero, twice. It is not: quiet hours costs about two and a half points of
  recovery, which is a cost we choose rather than one we had measured.

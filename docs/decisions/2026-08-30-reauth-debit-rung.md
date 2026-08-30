# One debit after a completed re-authorisation

**Written 2026-08-30, before the change is measured. No post-change number
existed when this was committed.**

## The gap

`REAUTH_REQUIRED` is 64 cases and **25.0% of at-risk value** on `batch_holdout`.
Capstan sends a re-authorisation link, walks the ladder, and escalates to a
human — those four ladders carry no debit rung, so a customer who *does*
re-authorise is never charged. The oracle says a re-auth link converts about
**43%** of the time (0.26–0.64), which makes the forgone expectation
**₹37,045 = 10.44pp of at-risk value** — larger than Capstan's entire current
lead over the baseline.

## The change

### Ladders — one debit rung each, gated

| cause | before | after | cap |
|---|---|---|---:|
| `CARD_EXPIRED` | `[REAUTH_LINK, CUSTOMER_NUDGE, HUMAN_ESCALATION]` | `[REAUTH_LINK, SCHEDULED_RETRY, CUSTOMER_NUDGE, HUMAN_ESCALATION]` | 0 → 1 |
| `MANDATE_LIMIT_EXCEEDED` | same as above | same as above | 0 → 1 |
| `MANDATE_EXPIRED` | same as above | same as above | 0 → 1 |
| `AUTHENTICATION_FAILED` | `[RAIL_SWITCH, REAUTH_LINK, ABANDON]` | `[RAIL_SWITCH, REAUTH_LINK, SCHEDULED_RETRY, ABANDON]` | 1 → 2 |

`SCHEDULED_RETRY: "+24h"` on all four — the customer is given a day to complete
the flow before anything is charged.

**`MANDATE_REVOKED` is deliberately excluded.** A customer who revoked their
mandate withdrew consent. No link, and no completion of one, makes charging them
acceptable; that ladder stays `[ABANDON]`.

### The gate — completed, not sent

A new guardrail **G12 (re-authorisation pending)** blocks any debit on a case
where a `REAUTH_LINK` has been sent and *not* completed, and advances the ladder.
It is N/A where no link was sent, and ALLOW once one has completed.

Completion has to be observable for that gate to mean anything, so it is modelled
as an observable event rather than a hidden coin flipped at debit time:

- `SimulatedGateway.sendCommunication` resolves the customer's response when a
  `REAUTH_LINK` is sent, deterministically from the case id against
  `nudge_sensitivity`, and records it.
- `PaymentGateway.reauthCompleted(caseId)` exposes it. `DebitWorker` persists
  `reauth_requested_at` and, when it converts, `reauth_completed_at` (+6h, a
  plausible customer response time, so the ladder's 24-hour wait is a real
  constraint rather than a formality).
- `railPermits("REAUTH_REQUIRED")` now requires **completion**, not merely a link
  having been sent — the same distinction corrected in the gateway earlier today.

**This does not change the probability of recovery.** Every `REAUTH_REQUIRED`
case in the batch has `attempt_success_prob = 1.000` and no recovery window, so
P(recover) is `nudge_sensitivity` before the change and
`nudge_sensitivity × 1.0` after it. The conversion roll moves from debit time to
comms time; its value does not move. What changes is *who can observe it and act
on it*.

### Ablation renaming

`REAUTH_ROUTING` currently raises the mandate cap so G4 never substitutes, which
is over-cap substitution rather than re-auth routing, and it measured +0.00pp.
It becomes `OVER_CAP_SUBSTITUTION`, and `REAUTH_ROUTING` becomes the new path:
clear `reauth_completed_at` so G12 always blocks.

## Predicted direction and magnitude, recorded before measuring

Theoretical ceiling for this change is the full ₹37,045 (+10.44pp on holdout).
Realistically it is reduced by cases whose billing cycle closes before the ladder
reaches the gated rung. Predictions:

| quantity | now | predicted |
|---|---:|---:|
| Capstan, holdout rupee recovery | 33.55% | **41–44%** (central +9pp) |
| Gap vs baseline, holdout | +3.29pp | **+11 to +14pp** |
| Sweep median delta | +5.85pp | **+13 to +17pp** |
| `re-auth routing` ablation | +0.00pp | **+7 to +10pp** (second only to payday timing) |
| Baseline, any batch | 30.26% | **unchanged** — it cannot send a `REAUTH_LINK` |
| Oracle upper bound | 83.29% | **unchanged** — the oracle is untouched |
| Misses "never attempted" | 58 | **30–40** (~57% still do not convert and stay ungated) |
| `escalatedToHuman`, holdout | 70 | **48–58** |
| Debit attempts, holdout | 374 | **390–405** |
| `duplicateChargesCaused` / `safetyFailures` | 0 / 0 | **0 / 0** |

**If it undershoots**, the most likely cause is the billing-cycle boundary: a case
that fails late in the cycle may not reach `REAUTH_LINK + 24h` before
`billing_cycle_end`, and the cycle guardrail terminates it. The second candidate
is G8's comms-frequency cap interacting with the added rung.

## Note on the pattern

This is the third simulator-touching change in this phase and all three have
helped Capstan. That is worth stating rather than hoping nobody notices. The two
before it were errors of fact — cases decided before they failed, and a dunning
SMS completing a mandate re-authorisation — and this one moves a probability from
an unobservable position to an observable one without changing its value. The
test applied each time is *would we make this change if it hurt us*. Here the
answer is yes: a merchant who receives a mandate re-registration webhook and does
not then charge is leaving money on the table for no reason, and a simulator that
cannot represent that is not representing the domain.

The falsifiable content is the table above. If the measured numbers fall outside
those ranges, that is reported as a miss rather than reframed.

---

# Outcome, recorded after measuring

**It undershot.** Five of seven predictions landed inside their ranges; the two
headline figures did not.

| quantity | predicted | measured | |
|---|---:|---:|:--|
| Capstan, holdout rupee recovery | 41–44% | **38.68%** | miss, low |
| Gap vs baseline, holdout | +11 to +14pp | **+8.42pp** | miss, low |
| `re-auth routing` ablation | +7 to +10pp | **+5.13pp** | miss, low |
| Baseline | unchanged | 30.26% | hit |
| Oracle upper bound | unchanged | 83.29% | hit |
| Debit attempts | 390–405 | 399 | hit |
| `escalatedToHuman` | 48–58 | 58 | hit |
| duplicates / safety failures | 0 / 0 | 0 / 0 | hit |

Captured 5.13pp of the 10.44pp theoretical — 49%, against the ~85% predicted.

## Both predicted failure modes were wrong

The note named the billing-cycle boundary and G8's comms cap. Neither did
anything. The actual cause:

| cause | cases | re-auth completed | recovered | blocked by |
|---|---:|---:|---:|---|
| `CARD_EXPIRED` | 27 | 12 | **12** | — |
| `AUTHENTICATION_FAILED` | 10 | 4 | **4** | — |
| `MANDATE_LIMIT_EXCEEDED` | 21 | 8 | **0** | G4 ×21 |
| `MANDATE_EXPIRED` | 6 | 2 | **0** | G3 |

Where re-authorisation was the only obstacle, conversion to recovery was
**16 for 16**. Where the mandate itself was still non-compliant, the guardrails
refused — and were right to. A completed re-authorisation does not refresh the
mandate in this model: the per-transaction cap stays breached, the validity
window stays lapsed, and debiting against either would be rejected by the issuer
anyway.

The arithmetic reconciles: the two blocked causes carry ₹15,501 of expectation,
4.37pp, and 5.13 + 4.37 = 9.50pp against a 9pp central estimate. The model of the
mechanism was right; the assumption that a completed re-auth clears every mandate
constraint was wrong.

## Not modelled, deliberately — and its cost is measured

This is **not** the same category as the `railPermits` correction. That one was
factually wrong in any payment system: an SMS does not re-authorise a mandate.
What a re-authorisation *refreshes* genuinely varies — re-registering a
card-on-file with a new instrument does clear an expiry, while a UPI Autopay
per-transaction cap is a property of the mandate registration and raising it
requires the customer to approve a *new* mandate rather than re-confirm the old
one. A model in which a completed re-auth does not silently raise a breached cap
is defensible, arguably more correct than the alternative.

So it is stated rather than modelled, with a magnitude from two independent
routes that agree to 0.01pp:

- **Oracle expectation:** ₹15,501 forgone = **4.37pp**
- **Measured by the `over-cap substitution` ablation:** removing the binding cap
  moves recovery 38.68% → 43.04% = **4.36pp**

27 of the 64 re-auth cases carry a mandate constraint that re-authorisation does
not clear here. In production the ceiling would be higher if the re-auth flow
issued a fresh mandate, and lower if it did not.

## Resolution note

Three mechanisms measured **+0.71pp** each on the holdout. One case at the median
ticket is 0.34pp, so those are one-to-two-case effects at the resolution limit of
a 300-case batch. The ten-seed sweep is the better read for anything that small.

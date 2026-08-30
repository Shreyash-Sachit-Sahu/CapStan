# Capstan · Phase 04 — Policy Engine & Guardrails

**Goal:** given a diagnosed case and its history, choose exactly one bounded
intervention or terminate the case — and emit a `DecisionRecord` that explains
why in a form a human can audit.

**Done when:** `POST /api/cases/{id}/decide` returns a `DecisionRecord`, every
guardrail has a unit test including its boundary, and no code path can produce an
action when a hard stop applies.

---

## 1. Stance: the model does not choose the action

This is the design decision that separates Capstan from a prompt with a webhook.
The LLM's job ended in Phase 03 — it read a messy string and produced a label.
**The action is chosen by a deterministic policy table, then filtered through
guardrails.** Reasons:

- Money actions must be reproducible. The same case must yield the same decision
  on every run, or the backtest means nothing.
- "Bounded and gated" is literally the phrasing in the buildathon bars.
- A judge can read the table and verify it. They cannot verify a prompt.

The LLM returns in one narrow, non-authoritative role: **drafting the customer
message copy** for a nudge whose sending was already authorised by the policy
(§6). Generation, not decision.

---

## 2. Intervention set

```java
public enum InterventionKind {
    RECONCILE_ONLY,        // no debit; ask the gateway what actually happened
    IMMEDIATE_RETRY,       // same rail, now — timeouts and transient declines
    SCHEDULED_RETRY,       // same rail, at a computed time
    PAYDAY_RETRY,          // same rail, at the next salary-cycle window
    RAIL_SWITCH,           // alternate instrument on file
    CUSTOMER_NUDGE,        // comms only, no debit
    REAUTH_LINK,           // send mandate re-authorisation link
    HUMAN_ESCALATION,      // queue for a human, stop automation
    ABANDON                // terminal, with reason
}
```

## 3. Policy table

Keep it in `policy/recovery_policy.yaml` so it is reviewable as a document, and
load it into an immutable in-memory structure at startup.

```yaml
version: 1
policies:
  - cause: NETWORK_TIMEOUT
    ladder: [RECONCILE_ONLY, IMMEDIATE_RETRY, SCHEDULED_RETRY]
    schedule: { SCHEDULED_RETRY: "+6h" }
    max_debit_attempts: 2
    note: "Reconcile first, always. The debit may already have succeeded."

  - cause: INSUFFICIENT_FUNDS
    ladder: [PAYDAY_RETRY, CUSTOMER_NUDGE, PAYDAY_RETRY, ABANDON]
    schedule: { PAYDAY_RETRY: "next_payday_window" }
    max_debit_attempts: 3
    max_nudges: 1
    note: "Retrying before the balance arrives burns an attempt for nothing."

  - cause: ISSUER_DECLINE_TEMPORARY
    ladder: [SCHEDULED_RETRY, SCHEDULED_RETRY, RAIL_SWITCH, ABANDON]
    schedule: { SCHEDULED_RETRY: "exp_backoff(4h, 2.0, jitter=0.2)" }
    max_debit_attempts: 3

  - cause: DO_NOT_HONOUR
    ladder: [SCHEDULED_RETRY, CUSTOMER_NUDGE, SCHEDULED_RETRY, ABANDON]
    schedule: { SCHEDULED_RETRY: "+24h" }
    max_debit_attempts: 2
    note: "Ambiguous. Two probes maximum, then ask the customer."

  - cause: CARD_EXPIRED
    ladder: [REAUTH_LINK, CUSTOMER_NUDGE, HUMAN_ESCALATION]
    max_debit_attempts: 0
    note: "No debit can succeed. Retrying is pure waste."

  - cause: MANDATE_LIMIT_EXCEEDED
    ladder: [REAUTH_LINK, CUSTOMER_NUDGE, HUMAN_ESCALATION]
    max_debit_attempts: 0

  # Added when the taxonomy gained MANDATE_EXPIRED. This table predated that fix
  # and would have left 6 cases per batch without a ladder.
  - cause: MANDATE_EXPIRED
    ladder: [REAUTH_LINK, CUSTOMER_NUDGE, HUMAN_ESCALATION]
    max_debit_attempts: 0

  - cause: AUTHENTICATION_FAILED
    ladder: [RAIL_SWITCH, REAUTH_LINK, ABANDON]
    max_debit_attempts: 1

  - cause: MANDATE_REVOKED
    ladder: [ABANDON]
    terminal_reason: "Customer withdrew authorisation"

  - cause: ACCOUNT_CLOSED
    ladder: [ABANDON]
    terminal_reason: "Underlying account closed"

  - cause: RISK_BLOCKED
    ladder: [HUMAN_ESCALATION]
    max_debit_attempts: 0
    note: "Hard stop. Automation must never retry a risk-blocked debit."

  - cause: TECHNICAL_DECLINE_UNKNOWN
    ladder: [SCHEDULED_RETRY, HUMAN_ESCALATION]
    schedule: { SCHEDULED_RETRY: "+12h" }
    max_debit_attempts: 1
    note: "Low confidence. One conservative probe, then a human."
```

### Low-confidence override

If `diagnosis_confidence < 0.60` or `diagnosis_method = ABSTAIN`, ignore the
cause-specific ladder and use the conservative ladder
`[SCHEDULED_RETRY(+12h), HUMAN_ESCALATION]` with `max_debit_attempts: 1`. Never
let an uncertain label authorise an aggressive ladder. Show this in the demo — it
is the answer to "what happens when your model is wrong."

### The UNDIAGNOSED branch — a debit needs an affirmative clearance, not a default

Abstention arrives here as `UNDIAGNOSED`, which is **not retryable** (Phase 03 §2
explains why). That is deliberate: the diagnosis layer is telling us it does not
know, and an unknown case must not inherit permission to debit.

But "we don't know the cause" is not the same as "we know nothing". We hold
records the payload never contained, and we can consult them the same way the
`mandate_not_active` refinement does — deterministically, against our own data.

Permit **exactly one** `SCHEDULED_RETRY` only if *all* of the following clear
affirmatively:

| Check | Must be |
|---|---|
| `mandate.status` | `ACTIVE` |
| now | within `valid_from .. valid_until` |
| `case.amount_paise` | ≤ `mandate.max_amount_paise` |
| customer risk flag | absent |

Any check failing **or unavailable** → `HUMAN_ESCALATION`, no debit.

The second half of that sentence is the load-bearing half. **Absence of a
clearance is not a clearance.** A null mandate status, an unreadable risk flag, a
query that errored — none of them mean "fine to proceed". This is the same class
of bug as the one that produced `UNDIAGNOSED` in the first place: a component's
failure mode meeting another component's default, and the default being
permissive.

Phase 07 reports how many cases took the cleared-retry path versus straight
escalation. If almost everything escalates, the checks are too strict and the
number says so; if almost nothing does, they are not doing any work.

### Payday window

`next_payday_window` = the next occurrence of the customer's inferred salary date
+ 6 hours, where the inferred date defaults to the 1st of the month and is
overridden by observed prior successful debit dates on the same mandate. If the
window falls after `billing_cycle_end`, the intervention is not schedulable and
the ladder advances. This one rule produces most of Capstan's lift over the fixed
T+1/T+3/T+5 baseline — make sure Phase 07 can attribute it.

---

## 4. Guardrails — evaluated after the ladder proposes an action

**Every guardrail is evaluated on every decision. The first non-ALLOW in table
order decides.** Those are two separate things and both matter: the full
evaluation is the audit artifact, the ordering is the control flow. Guardrail
evaluation is therefore side-effect-free, so running all eleven when only the
first decisive one changes anything is always safe.

Results are recorded as `ALLOW`, `N/A` (with a reason it did not apply), `DEFER`,
or `BLOCK`. `N/A` entries are not filler — "G7 did not apply because this is not a
comms action" is what makes the trail readable to someone checking whether the
system is bounded.

A `BLOCK` additionally carries a **disposition**, because the table specifies
three different things that all read as "block":

| Disposition | Used by | Meaning |
|---|---|---|
| `ADVANCE_LADDER` | G1, G3, G5, G8, G9 | Try the next rung |
| `SUBSTITUTE(action)` | G4 → `REAUTH_LINK`, G10 → `HUMAN_ESCALATION` | Replace with a safer action |
| `TERMINATE(status)` | G2 → `EXPIRED` | End the case |

A substituted action is **re-checked against every guardrail**. A re-auth link
substituted for an over-cap debit is comms, and must still respect opt-out and
quiet hours — substitution is not an escape hatch.

When a guardrail terminates a case, the final action is `ABANDON`, **not** the
action that was proposed. Leaving the proposal in place writes a debit into
`intervention.kind` for Phase 05 to pick up and execute on a case that was
supposed to have stopped. The property test caught exactly this.

Likewise, when a guardrail substitutes into a terminal action, the terminal
reason is **the guardrail's**, not the ladder's. A risk-hold escalation that
inherits `INSUFFICIENT_FUNDS`'s exhaustion reason claims the balance never
arrived, which is not what stopped it, and would file the case under the wrong
heading in Phase 07's exception list.

| # | Guardrail | Rule |
|---|---|---|
| G1 | Opt-out | Customer opted out of contact → BLOCK all comms interventions. Debits unaffected (they are contractual, not marketing). |
| G2 | Cycle boundary | `now > billing_cycle_end` → BLOCK, terminate `EXPIRED`. |
| G3 | Mandate validity | Debit action while mandate is REVOKED/EXPIRED or outside `valid_from..valid_until` → BLOCK. |
| G4 | Mandate cap | `amount_paise > mandate.max_amount_paise` → BLOCK debit, propose `REAUTH_LINK`. |
| G5 | Attempt cap | Debit attempts on case ≥ `max_debit_attempts` → BLOCK, advance ladder. |
| G6 | Cooling-off | < 4h since last debit attempt → DEFER. |
| G7 | Quiet hours | Comms between 21:00–09:00 IST → DEFER to 09:15 IST. Debits are exempt (machine-to-machine). |
| G8 | Comms frequency | ≥ 1 message in 48h, or ≥ 3 in the cycle → BLOCK. |
| G9 | Rail availability | `RAIL_SWITCH` with no `alternate_rail` → BLOCK, advance ladder. |
| G10 | Risk hold | `RISK_BLOCKED` cause, or a risk flag on the customer → BLOCK everything except `HUMAN_ESCALATION`. |
| G11 | Global budget | Batch-level circuit breaker: > 25% of attempts in the last 15 min failed with the same issuer code → DEFER all debits on that issuer 1h. |

G11 exists because it is the one guardrail that shows systems thinking: if an
issuer is down, hammering it makes recovery worse for everyone. It also gives the
demo a nice moment.

**G11 groups by failure reason, not by issuer — say so, don't hide it.**
Razorpay's error payload carries no issuer identifier, and we did not invent a
column to pretend otherwise. Grouping recent failed attempts by
`recovery_case.raw_error_reason` is a real proxy with a real limitation: two
different banks failing for the same reason are counted together. On production
data with issuer identifiers you would group by those and the rest of the rule is
unchanged. Naming the approximation is a better answer to "would this work in
production" than a fake column would be.

It also needs a **minimum sample of 4** before it can fire. One failure out of one
attempt is a 100% failure rate and means nothing.

**Quiet hours implementation note:** convert explicitly, do not rely on the
default zone (Phase 01 pinned the JVM to UTC on purpose).

```java
ZonedDateTime ist = instant.atZone(ZoneId.of("Asia/Kolkata"));
LocalTime t = ist.toLocalTime();
boolean quiet = t.isBefore(LocalTime.of(9, 0)) || !t.isBefore(LocalTime.of(21, 0));
```

Unit-test 20:59:59, 21:00:00, 08:59:59, 09:00:00 IST, plus a UTC instant that is
"yesterday" in UTC but inside quiet hours in IST.

---

## 5. DecisionRecord — the audit artifact

Persisted verbatim into `intervention.decision_json` and surfaced in the cockpit's
why-panel. This is the object a judge reads when they ask "why did it do that."

**`DecisionService` does not write `expires_at`.** It originally set
`scheduled_for + 6h` there, which was wrong once Phase 05 defined what the column
means. A scheduled window and a claim lease are different things, and the one
name was carrying both: the window says when an action *may* run, the lease says
who is running it *right now*. Phase 05's `uq_case_in_flight` reads a non-NULL
`expires_at` as "this case is claimed", so writing it at decision time would mark
every freshly decided intervention as already in flight and reject the case's
next decision. The worker sets it in the same statement that claims the row, and
clears it when the outcome lands.

```json
{
  "decisionId": "b1c9…",
  "caseId": "8f2a…",
  "at": "2026-09-03T14:02:11Z",
  "policyVersion": 1,
  "diagnosis": {
    "cause": "INSUFFICIENT_FUNDS",
    "confidence": 0.99,
    "method": "CODE_MAP",
    "evidence": "error_code=BAD_REQUEST_ERROR_INSUFFICIENT_BALANCE"
  },
  "ladderPosition": 1,
  "proposedAction": "PAYDAY_RETRY",
  "guardrails": [
    {"id": "G1", "result": "ALLOW"},
    {"id": "G5", "result": "ALLOW", "detail": "attempts 1/3"},
    {"id": "G6", "result": "ALLOW", "detail": "last attempt 38h ago"},
    {"id": "G7", "result": "N/A",   "detail": "not a comms action"}
  ],
  "finalAction": "PAYDAY_RETRY",
  "scheduledFor": "2026-10-01T03:30:00Z",
  "scheduleRationale": "Inferred salary date 1st; +6h buffer; within cycle end 2026-10-05",
  "amountAtRiskPaise": 49900,
  "stopConditions": ["max_debit_attempts=3", "billing_cycle_end=2026-10-05T18:30:00Z"],
  "humanReadable": "Balance was short. Waiting for the 1st rather than retrying now, because a retry before salary credit would spend one of three permitted attempts for nothing."
}
```

`amountAtRiskPaise`, not `expectedValuePaise`. It is the case amount, unweighted —
weighting it by a probability of success would need the oracle, which is not
available at decision time and would be cheating if it were. Calling an
unweighted amount an expected value is the kind of thing a judge notices, and the
ladder ordering stays purely policy-driven rather than sneaking in value ranking.

The `humanReadable` line is the only field the LLM may write, and it is written
*from* the structured record — never the other way around. If the model is
unavailable, fall back to a template string. The record must be complete without
it.

Two additions the implementation needed:

- **`decisionId` is derived, not random.** `UUID.nameUUIDFromBytes` over case id,
  ladder position, instant and action. A random id makes every record differ and
  quietly defeats the reproducibility §1 requires.
- **Each guardrail entry carries `appliedTo`.** The ladder can be walked more than
  once per decision, so "G5 BLOCK" without the action it blocked is not an
  explanation.

---

## 6. LLM's remaining job: nudge copy

When policy authorises `CUSTOMER_NUDGE` or `REAUTH_LINK`, generate the message.

- Locale-aware: `en-IN` and Hinglish variants (`preferred_locale` on the customer).
- Constraints in the prompt: no urgency manipulation, no threat of service loss
  beyond the factual contractual position, no rupee amount invention — the amount
  and due date are injected, not generated.
- Output passes a **validator** before send: must contain the injected amount
  string, must not contain a link other than the injected one, must be under 320
  chars, must not contain the words "immediately", "final warning", "legal".
  Failing validation → fall back to the template. Log the rejection.

That validator is a small thing that reads as maturity. Include a test for it.

---

## 7. Acceptance checks

```bash
./mvnw test -Dtest=PolicyEngineTest          # ladder, risk hard stop, low confidence,
                                             # UNDIAGNOSED clearance, guardrail recording,
                                             # reproducibility, nudge copy validator
./mvnw test -Dtest=GuardrailBoundaryTest     # 20:59:59 / 21:00:00 IST etc.
./mvnw test -Dtest=PolicyInvariantPropertyTest
```

The named cases from earlier drafts are `@Nested` classes inside
`PolicyEngineTest`, so they still run and report under their own names.

**The property test earned its place immediately.** Over 10,000 generated
histories it asserts three invariants — no case exceeds `max_debit_attempts`, no
case is left non-terminal past `billing_cycle_end`, and every terminal case
carries a non-null `terminal_reason` — plus reproducibility, that the same
context yields an identical record twice.

It found a real defect on the first run that no unit test had: when G2 terminated
a case past its cycle end, the engine left `finalAction` as the *proposed debit*.
The case was correctly marked terminal, so every status assertion passed — but the
`intervention` row carried `kind=SCHEDULED_RETRY`, and Phase 05 would have read it
and executed a debit on a case that had already stopped. That is the class of bug
the note about ordering was pointing at: a component's failure mode meeting
another component's default.

Two guards on the guards are worth keeping: the property test asserts at least one
history authorised a debit (otherwise the cap passes vacuously on dead-end cases),
and the ladder test asserts terminal-by-behaviour rather than by ladder shape —
`NETWORK_TIMEOUT`'s ladder has no explicit terminal rung and terminates by
exhaustion, which is equally valid and would have been masked by a shape check.

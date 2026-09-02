# Capstan — recording script

Read-aloud version. `DEMO.md` is the director's document (stage directions,
recovery, judge Q&A); this one is just the words, in the order you say them.

**Before you hit record:** `bash scripts/demo-preflight.sh` must end in
**ALL CHECKS PASSED**. It prints the tamper command with the id already filled
in — paste that, never retype it.

**Length: 881 spoken words, about 5:50 at a normal pace.** That is over the
original four-minute target and the technical detail is why. If you need 4:00,
cut from 0:30 and 2:25 — they carry the most explanation. Leave 1:35 intact; the
exactly-once argument is the strongest thing in the run.

Every figure below is in `docs/report_holdout.json` and its two companions.

---

## Numbers you must hit

Say the rounded form aloud. The exact form is what the screen shows and what a
judge will check against. Both are correct; do not quote the exact form over the
chart, because a median of rates is not the aggregate ratio and someone doing the
arithmetic will get a number that does not reconcile.

| | say | screen shows |
|---|---|---|
| Baseline, share of value at risk | "twenty-nine percent" | 28.94% |
| Capstan | "thirty-eight percent" | 38.39% |
| Oracle ceiling | "seventy-nine percent" | 79.00% |
| At risk, sweep | "thirty-six and a half lakh" | ₹36.47L |
| Baseline recovered | "ten point nine lakh" | ₹10.87L |
| Capstan recovered | "fourteen" | ₹14.04L |

Everything else is spoken as written.

**Sweep figures are safe to speak; single-batch figures are not.** Every number
in the table above comes from the ten-batch sweep and reproduces exactly on a
re-run. The per-case counts on tabs 3 and 4 do not: payday inference derives a
customer's salary day from prior successful debits, `clearExecutionState` wipes
`payment_attempt` before every run, so a standalone run starts payday-blind and
lands lower than one that followed other runs. Read anything on tabs 3 and 4 off
the screen. The beats below are written so you never have to speak one from
memory.

---

## 0:00 — 0:30 · The bracket

**[Tab 1 — cockpit home. The ratchet is on screen. Do not mention the stack.]**

> Three thousand failed e-mandate debits, across ten independently seeded
> fixtures. **Thirty-six and a half lakh rupees** at risk.
>
> A fixed retry ladder — T plus one, T plus three, T plus five, same rail
> regardless of cause — recovers **ten point nine lakh**.
>
> Capstan recovers **fourteen**.
>
> As a share of value at risk: **twenty-nine percent** for the ladder,
> **thirty-eight** for us. Both arms scored by the same oracle, through the same
> simulated gateway, on the same fixtures. With perfect foresight the ceiling is
> **seventy-nine**, and we publish it, because an accuracy number without a
> ceiling implies a target that does not exist.
>
> We are ahead on nine of the ten.

---

## 0:30 — 1:05 · The number that matters more

**[Tab 1 — scroll to the equal-budget table.]**

> Recovery rate is the wrong headline, because the baseline buys it with volume
> and the scoring function prices an attempt at zero.
>
> So cap both arms at the same debits per case. One attempt each: baseline
> **eleven percent**, us **twenty-two**. Two attempts: **twenty-seven** against
> **thirty-two**.
>
> We win at every budget where the cap binds, and across the sweep we spend
> **fifty-two percent** of the baseline's debit volume.
>
> This is a cost-per-recovery argument. We deliberately did not add a cost model
> — a per-attempt fee, churn, refund handling — because choosing a scoring
> function after seeing results is unfalsifiable.

---

## 1:05 — 1:35 · Which decision earns it

**[Tab 1 — scroll to the ablation bars.]**

> Each mechanism switched off in turn, against the same fixtures. One dominates:
> not retrying before payday. **Nearly twelve points.**
>
> That is more than our entire lead. Ablate payday timing and we recover
> **twenty-three percent** against the baseline's **thirty**. We lose.
>
> The naive ladder fires at T plus one into an account it already has evidence is
> empty, and spends an attempt to learn nothing. Payday is inferred from prior
> successful debits on the same mandate, and it is the first thing we would
> replace with a learned per-customer model.

---

## 1:35 — 2:25 · The thing nobody else has

**[Tab 2 — case timeline. Walk down the rows as you speak.]**

> This customer was charged. The gateway took the money and the response never
> came back.
>
> `DEBIT_INITIATED`. Then `ATTEMPT_UNKNOWN` — not failed, **unknown**. That is a
> distinct terminal state in our schema, and the distinction is the whole point:
> `FAILED` is a permission, it authorises the next debit. We have not established
> that anything failed, so we do not grant it.
>
> `RECONCILE_ATTEMPTED`. We asked the gateway what actually happened, keyed by an
> idempotency key derived from case, sequence, rail and amount — deterministic,
> so the same logical attempt always produces the same key. It had succeeded.
> Case recovered. **One debit.**
>
> That key is `uq_idem`, a unique constraint on `payment_attempt`. A duplicate
> debit is not a bug we avoided, it is a row the database will not accept. The
> debit cap is a second constraint, `uq_case_debit_seq`, so a race cannot exceed
> it either. Across the sweep the baseline double-charged **thirty-three**
> customers. We charged **zero**.

**[Scroll to the audit chain. Click Verify. Green.]**

> Every event is hash-chained over canonical JSON, and `audit_event` is
> append-only by database trigger.

**[Run the tamper command from the pre-flight. Click Verify again. Red.]**

> To do that we had to disable the trigger, which needs DDL privilege. The chain
> caught it anyway, and it names the sequence number where the hash breaks.

---

## 2:25 — 3:10 · Fallible model, safe system

**[Tab 3 — the misdiagnosed case.]**

> Diagnosis is a cascade. Tier one is a deterministic map over Razorpay's
> `source:step:reason` triple — seventy-five percent of cases at **one point
> zero** accuracy. Tier three is a closed-set LLM classifier for the payloads
> where the bank declined without disclosing a reason.
>
> Here is one it got wrong. Reason `payment_declined`, narration the single word
> `FAILED`. There is no signal in that payload.
>
> Tier three answered `DO_NOT_HONOUR` at confidence **zero point eight**. The
> truth was `RISK_BLOCKED` — a fraud hold.
>
> `DO_NOT_HONOUR` is retryable. That confident wrong answer is a green light to
> debit a fraud-blocked customer.

**[Point at the greyed, struck-through row.]**

> **G10, risk hold. Zero debits.** Every risk-blocked case in this batch: zero.
> Across the sweep the baseline put **two hundred and seventy** debits on
> fraud-blocked customers. We put none.
>
> The model is fallible and it does not matter, because `PolicyEngine` holds no
> classifier reference. The model cannot reach a decision path even by accident
> — the class has no way to call one. It classifies. It never decides.

---

## 3:10 — 3:45 · The misses

**[Tab 4 — exceptions.]**

> These are the cases we did not recover on this batch — one of the ten. The
> count is on your screen; say it. Every one is listed with its diagnosed cause,
> its true cause, the ladder rung it died on, and the guardrail that stopped it.
>
> They are not a diagnosis problem. We diagnosed most of them correctly. They are
> boundedness — we named the cause, tried the number of times the policy permits,
> and stopped. The baseline does not stop.

**[Point at the correctly-abandoned and correctly-escalated groups.]**

> These two groups are not failures. The oracle says the first group was never
> recoverable at all, and the second was routed to a human because no safe
> automated action remained. Read both counts off the screen.
>
> A system that knows when to stop has to be allowed to stop.

---

## 3:45 — 4:00 · Close

**[Back to Tab 1, or your face. Slow down here.]**

> Every money action is diagnosed, bounded, gated, logged and measured. The
> comparison is against a fair baseline, scored by the same oracle. We publish
> the ceiling, and we publish the misses.
>
> And we made four calls against our own interest.
>
> We gave the holdout a vocabulary our rules had never seen, and our reported
> accuracy dropped three points. We left a simulator bias in place that would
> raise our numbers if we corrected it. We declined to model a limitation worth
> eight points of our own expectation. And we refused a cost model that would flip
> the ranking our way.
>
> The README has the full audit.

---

## Delivery notes

- **Slow on the numbers, quick on the connective tissue.** Every figure is a
  claim someone may check; give each one its own beat.
- **The two strongest moments are the tamper going red and "turn payday timing
  off and we lose."** Pause after both. Do not rush past your own evidence.
- **Do not apologise for the simulated gateway.** If it comes up, the honest line
  is in DEMO.md's Q&A: the adapter is written against documented test-mode
  endpoints, the recurring leg needs a tokenised mandate that cannot be completed
  headlessly, and every number here runs through the simulator. Say it flat.
- **Nothing you click starts a job.** The cockpit reads a persisted report. A run
  takes eighty seconds and must never happen on camera.
- **If you fluff a line, stop and retake the section.** These are short blocks on
  purpose so a retake costs thirty seconds, not four minutes.
- **Record one safety take of the whole thing before you start polishing.** A
  finished mediocre take beats an unfinished good one.

# Capstan — recording script

Read-aloud version. `DEMO.md` is the director's document (stage directions,
recovery, judge Q&A); this one is just the words, in the order you say them.

**Before you hit record:** `bash scripts/demo-preflight.sh` must end in
**ALL CHECKS PASSED**. It prints the tamper command with the id already filled
in — paste that, never retype it.

**Length: 652 spoken words.** That is 4:20 of speech at a deliberate 150 words
per minute, plus roughly twenty seconds of dead air in the 1:22 beat while you
run the tamper command and click Verify twice. Call it **4:40 as written**, or
about **4:00 if you present at 165**, which is a normal rehearsed pace.

If you need a hard 4:00 without speeding up, the next cut is the model beat at
2:35 — losing the tier-cascade sentence saves about twenty seconds and costs the
setup for why the wrong answer was plausible. Do not cut 1:22; the exactly-once
argument is the strongest thing in the run and it is the only beat with a live
demonstration behind it.

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

> Three thousand failed e-mandate debits, ten independently seeded fixtures,
> **thirty-six and a half lakh rupees** at risk.
>
> A fixed retry ladder — T plus one, three, five, same rail regardless of cause —
> recovers **ten point nine lakh**. Capstan recovers **fourteen**.
>
> As a share of value at risk: **twenty-nine percent** for the ladder,
> **thirty-eight** for us. Same oracle, same gateway, same fixtures. Perfect
> foresight would get **seventy-nine**, and we publish that ceiling.
>
> We are ahead on nine of the ten.

---

## 0:30 — 1:00 · The number that matters more

**[Tab 1 — scroll to the equal-budget table.]**

> Recovery rate is the wrong headline: the baseline buys it with volume, and the
> metric prices an attempt at zero.
>
> Cap both arms at the same debits per case. One attempt each: baseline
> **eleven percent**, us **twenty-two**. Two attempts: **twenty-seven** against
> **thirty-two**. We win wherever the cap binds, spending **fifty-two percent**
> of the baseline's debit volume.
>
> We deliberately did not add a cost model, because choosing a scoring function
> after seeing results is unfalsifiable.

---

## 1:00 — 1:22 · Which decision earns it

**[Tab 1 — scroll to the ablation bars.]**

> Each mechanism switched off in turn. One dominates: not retrying before payday.
> **Nearly twelve points** — more than our entire lead.
>
> Ablate it and we recover **twenty-three percent** against the baseline's
> **thirty**. We lose.
>
> The naive ladder fires at T plus one into an account it already has evidence is
> empty, and spends an attempt to learn nothing.

---

## 1:22 — 2:35 · The thing nobody else has

**[Tab 2 — case timeline. Walk down the rows as you speak.]**

> This customer was charged. The gateway took the money and the response never
> came back.
>
> `DEBIT_INITIATED`. Then `ATTEMPT_UNKNOWN` — not failed, **unknown**. `FAILED`
> is a permission: it authorises the next debit. We have not established that
> anything failed, so we do not grant it.
>
> `RECONCILE_ATTEMPTED`. We asked the gateway what happened, keyed by an
> idempotency key derived from case, sequence, rail and amount. It had succeeded.
> Case recovered. **One debit.**
>
> That key is `uq_idem`, a unique constraint on `payment_attempt`. A duplicate
> debit is not a bug we avoided, it is a row the database refuses. Across the
> sweep the baseline double-charged **thirty-three** customers. We charged
> **zero**.

**[Scroll to the audit chain. Click Verify. Green.]**

> Every event is hash-chained over canonical JSON, and `audit_event` is
> append-only by database trigger.

**[Run the tamper command from the pre-flight. Click Verify again. Red.]**

> To do that we had to disable the trigger, which needs DDL privilege. The chain
> caught it anyway, and it names the sequence where the hash breaks.

---

## 2:35 — 3:22 · Fallible model, safe system

**[Tab 3 — the misdiagnosed case.]**

> Diagnosis is a cascade: a deterministic map over Razorpay's
> `source:step:reason` takes seventy-five percent at **one point zero**, and a
> closed-set classifier takes the rest.
>
> Here is one it got wrong. Narration: the single word `FAILED`. It answered
> `DO_NOT_HONOUR` at confidence **zero point eight**; the truth was
> `RISK_BLOCKED`, a fraud hold. `DO_NOT_HONOUR` is retryable — a green light to
> debit a fraud-blocked customer.

**[Point at the greyed, struck-through row.]**

> **G10, risk hold. Zero debits.** Across the sweep the baseline put **two
> hundred and seventy** debits on fraud-blocked customers. We put none.
>
> The model is fallible and it does not matter: `PolicyEngine` holds no
> classifier reference. It cannot reach a decision path even by accident. The
> model classifies. It never decides.

---

## 3:22 — 3:58 · The misses

**[Tab 4 — exceptions.]**

> The cases we did not recover — count is on your screen, say it. Each with its
> diagnosed cause, true cause, the rung it died on, and the guardrail that
> stopped it.
>
> Not a diagnosis problem. Boundedness: we named the cause, tried what policy
> permits, and stopped. The baseline does not stop.

**[Point at the correctly-abandoned and correctly-escalated groups.]**

> These two groups are not failures. The oracle says the first was never
> recoverable; the second went to a human because no safe automated action
> remained.
>
> A system that knows when to stop has to be allowed to stop.

---

## 3:58 — 4:32 · Close

**[Back to Tab 1, or your face. Slow down here.]**

> Every money action is diagnosed, bounded, gated, logged and measured, against a
> fair baseline scored by the same oracle. We publish the ceiling and the misses.
>
> And four calls against our own interest: a holdout vocabulary our rules had
> never seen, costing three points of accuracy; a simulator bias left in place
> because correcting it would help us; a limitation worth eight points we stated
> rather than modelled; and a cost model we refused, because it would have
> flipped the ranking our way.
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

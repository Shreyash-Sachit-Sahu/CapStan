# Capstan — recording script

Read-aloud version. `DEMO.md` is the director's document (stage directions,
recovery, judge Q&A); this one is just the words, in the order you say them.

**Before you hit record:** `bash scripts/demo-preflight.sh` must end in
**ALL CHECKS PASSED**. It prints the tamper command with the id already filled
in — paste that, never retype it.

Target **4:00**. Roughly 600 words at a normal pace. Every figure below is in
`docs/report_holdout.json` and its two companions.

---

## Numbers you must hit

Say the rounded form aloud. The exact form is what the screen shows and what a
judge will check against. Both are correct; do not quote the exact form over the
chart, because a median of rates is not the aggregate ratio and someone doing the
arithmetic will get a number that does not reconcile.

| | say | screen shows |
|---|---|---|
| Baseline, share of value at risk | "twenty-nine percent" | 28.94% |
| Capstan | "forty-two percent" | 41.76% |
| Oracle ceiling | "seventy-nine percent" | 79.00% |
| At risk, sweep | "thirty-six and a half lakh" | ₹36.47L |
| Missed, this batch | read it off tab 4 | changes when the batch is re-run |
| Baseline recovered | "ten point nine lakh" | ₹10.87L |
| Capstan recovered | "fifteen point two lakh" | ₹15.20L |

Everything else is spoken as written.

---

## 0:00 — 0:30 · The bracket

**[Tab 1 — cockpit home. The ratchet is on screen. Do not mention the stack.]**

> Three thousand failed mandate debits, across ten independently generated
> batches. **Thirty-six and a half lakh rupees** at risk.
>
> A standard fixed retry ladder — T plus one, T plus three, T plus five —
> recovers **ten point nine lakh** of that.
>
> Capstan recovers **fifteen point two**.
>
> As a share of value at risk: **twenty-nine percent** for the ladder,
> **forty-two** for us. With perfect foresight, the ceiling is **seventy-nine**.
>
> We are ahead on all ten batches.

---

## 0:30 — 1:05 · The number that matters more

**[Tab 1 — scroll to the equal-budget table.]**

> But recovery rate is the wrong headline, because the baseline buys it with
> volume.
>
> Cap both arms at the same number of debits per case. One attempt each: the
> baseline gets **eleven percent**. We get **twenty-five**. Two attempts:
> **twenty-seven** against **thirty-seven**.
>
> We win at every budget where the cap actually binds. And across the sweep we
> use **forty-nine percent** of the baseline's attempt volume.
>
> This is a cost-per-recovery argument, not a recovery-rate one.

---

## 1:05 — 1:35 · Which decision earns it

**[Tab 1 — scroll to the ablation bars.]**

> We switched each mechanism off in turn, to see what it was worth. One
> dominates: not retrying before payday. **Fifteen and a half points**.
>
> That is more than our entire lead. Turn payday timing off and we recover
> **twenty-three percent**, against the baseline's **thirty**. We lose.
>
> The naive ladder retries at T plus one, into an account we already know is
> empty, and burns an attempt to learn nothing.

---

## 1:35 — 2:25 · The thing nobody else has

**[Tab 2 — case timeline. Walk down the rows as you speak.]**

> This customer was charged. The gateway took the money, and the response never
> came back.
>
> Debit initiated. Attempt **unknown** — not failed, *unknown*. That is a
> first-class state here. Most systems collapse unknown into failed and retry,
> and that is exactly where double charges come from.
>
> Reconcile attempted. We asked the gateway what actually happened, keyed by a
> deterministic idempotency key. It had succeeded. Case recovered. **One debit.**
>
> That key is a unique constraint in Postgres. A duplicate debit is not a bug we
> avoided — it is a row that cannot exist. Across the sweep, the baseline
> double-charged **thirty-three** customers. We charged **zero**.

**[Scroll to the audit chain. Click Verify. Green.]**

> Every one of those events is hash-chained, and the table is append-only by
> database trigger.

**[Run the tamper command from the pre-flight. Click Verify again. Red.]**

> To do that, we had to switch off a database trigger. The chain caught it
> anyway.

---

## 2:25 — 3:10 · Fallible model, safe system

**[Tab 3 — the misdiagnosed case.]**

> Here is a case our model got wrong. The bank declined without a reason, and the
> narration said only "FAILED". There is genuinely no signal in that payload.
>
> Tier three answered "do not honour", at confidence **zero point eight**. The
> truth was **risk blocked** — a fraud hold.
>
> "Do not honour" is retryable. That confident wrong answer is a green light to
> debit a fraud-blocked customer.
>
> Look what happened.

**[Point at the greyed, struck-through row.]**

> **G10, risk hold. Zero debits.** Across all nine risk-blocked cases in this
> batch: zero. The baseline made **twenty-seven**, and **two hundred and
> seventy** across the sweep.
>
> Our model is fallible, and it does not matter. That is the whole design. The
> model classifies. It never decides.

---

## 3:10 — 3:45 · The misses

**[Tab 4 — exceptions.]**

> On this batch — one of the ten — **[read the Missed count off the screen]**
> recoverable cases we did not get. They are all here, with what went wrong and
> what a human should do next.
>
> They are not a diagnosis problem. We diagnosed most of them correctly. They are
> boundedness. We named the cause, tried the number of times policy permits, and
> stopped. The baseline does not stop.

**[Point at the correctly-abandoned and correctly-escalated groups.]**

> And these two groups are not failures. On the same batch, **sixty-three** were
> never recoverable, and **forty-seven** need a human.
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
> four points of our own expectation. And we refused a cost model that would flip
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

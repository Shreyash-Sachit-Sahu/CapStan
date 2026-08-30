# Capstan · Phase 09 — Demo, Hardening, Submission Pack

**Goal:** convert a working system into a submission that wins. Different skill
from building it. Budget three hours and do not compress this phase — most teams
that lose with good systems lose here.

---

## 1. The demo script (target: 4 minutes, rehearsed twice)

Do not narrate the architecture. Show money, then show the guardrail, then show
the thing nobody else has.

**0:00 — 0:25 · The problem, in one number**
Open on `/`. "Three hundred failed mandate debits, ₹41.8 lakh at risk. A standard
fixed retry ladder recovers 41% of that. Capstan recovers 69%. The ceiling, if
you knew everything in advance, is 79%." Point at the ratchet scale. Say nothing
about the stack yet.

**0:25 — 1:10 · Why it recovers more**
Ablation waterfall. "Half the lift is one decision: not retrying before payday.
The naive ladder retries at T+1 into an account we already know is empty, and
burns one of three permitted attempts to learn nothing."

Open one insufficient-funds case. Read the `humanReadable` line off the
why-panel. That single sentence does more work than a diagram.

**1:10 — 2:00 · Why it is safe**
Same case view, scroll to a suppressed action. "At 22:40 IST the policy wanted to
send a nudge. G7 held it until 09:15. The trail records the message we did not
send." Then open a `RISK_BLOCKED` case: "no automated action exists for this
cause. It goes to a human, always."

Show the guardrail activity table. "Every bound in the system is a row here with
a count."

**2:00 — 3:10 · The thing nobody else has**
The timeout case, deep-linked. Walk the timeline: debit initiated → response
lost → attempt marked UNKNOWN → reconcile by idempotency key → the debit had
already succeeded → case recovered, queued retry cancelled.

"The naive ladder double-charged six real customers in this batch. We charged
zero. The idempotency key is a unique constraint in Postgres, so a duplicate
debit is not a bug we avoided, it is a row that cannot exist."

Then run the tamper endpoint live and show `/api/ledger/verify` going red.
Five seconds, enormous effect.

**3:10 — 3:45 · The misses**
`/exceptions`. "Nineteen recoverable cases we did not get. Here they are, with
what went wrong. Fourteen are one diagnosis confusion — do-not-honour read as
insufficient funds — and the fix is a better narration rule, not a bigger model."

Owning your misses out loud, on stage, with the list on screen, is the most
underrated move available.

**3:45 — 4:00 · Close**
"Every money action is diagnosed, bounded, gated, logged, and measured. It runs
against Razorpay test-mode APIs. The comparison is against a fair baseline scored
by the same oracle, and we publish the ceiling and the failures."

### Rehearsal rules
- Backend and frontend already running before you start. Never build on stage.
- Batch pre-loaded, backtest pre-run, results cached.
- Two deep links open in tabs: the payday case and the timeout case.
- Record a screen capture as a fallback the night before. Wifi fails at hackathons.
- If the LLM API is down: Tier 1 and Tier 2 still diagnose 85% of cases and the
  policy engine is fully deterministic. Say so and continue. Build this fallback
  path *before* demo day, not during it.

---

## 2. README structure

Judges skim. Order it for skimming.

```
# Capstan
One line: what it does and the headline number.

## Result            ← table first, before any architecture
baseline / capstan / upper bound, on the holdout batch, median of 10 seeds
duplicate charges: baseline 6, capstan 0
wasted attempt rate: baseline X%, capstan Y%

## How it decides    ← the policy table, rendered as a table
## How it stays bounded  ← G1–G11, one line each
## How we measured   ← the oracle disclosure, the fair-baseline statement
## What it missed    ← the full missed list
## Architecture      ← the diagram from brief 00
## Run it            ← 4 commands, tested from a clean clone
## What we'd do next ← 5 honest bullets
```

The oracle disclosure paragraph, verbatim-ish:

> Capstan is evaluated on synthetic data because measuring recovery requires
> knowing the counterfactual, which real data cannot provide. Each case carries a
> hidden recoverability function; the baseline and Capstan are scored by the same
> function through the same simulated gateway, and neither policy can read it —
> enforced by package isolation and a CI grep. Absolute rupee figures are
> synthetic. The comparison between arms, and the ceiling we report against, are
> the claim.

---

## 3. Hardening pass (2 hours, in this order)

1. **Clean-clone run.** `git clone` into a fresh directory, follow your own
   README exactly, time it. Anything that fails or needs undocumented knowledge
   gets fixed now. Do this on a different machine if you can.
2. **Kill and restart mid-batch.** The outbox and the reconcile scheduler must
   pick up where they left off. If they don't, that is a real bug and the demo
   will find it.
3. **LLM-down drill.** Set an invalid API key. Confirm diagnosis degrades to
   Tier 1/2 + ABSTAIN, the conservative ladder engages, and nothing hangs.
4. **Empty and error states** in the cockpit. A blank dashboard on stage because
   no batch was loaded is a preventable disaster.
5. **`@Profile("demo")` audit.** Confirm the tamper endpoint and any seed/reset
   endpoints are not reachable in the default profile.
6. **Secrets.** No `.env`, no API key, no test-mode credential in git history.
   `git log -p | grep -i -E "sk-|rzp_|api[_-]key"` — actually run it.

---

## 4. Submission pack

- Repo, public, with the README above and a tagged commit.
- 4-minute demo video (the rehearsed script, screen recording, your voice).
- `docs/report_holdout.json` — the raw metrics output, committed. Judges who care
  will open it, and its existence signals the numbers are real.
- `docs/architecture.md` — brief 00, lightly edited.
- One-page PDF: the ratchet-scale screenshot, the ablation table, the guardrail
  table, the misses count. This is what gets forwarded to someone who was not in
  the room.

---

## 5. Anticipated judge questions — have the answer ready

**"How do we know your numbers aren't rigged?"**
Same oracle for both arms, package-isolated from the agent, CI grep enforcing it,
ceiling reported, misses published, ten seeds with IQR.

**"What's actually AI here versus rules?"**
Diagnosis of ambiguous and Hinglish failure narrations — 15% of cases the rule
table cannot reach, at 0.7X+ accuracy against ABSTAIN as the alternative — plus
customer message drafting behind a validator. The *decisions* are deterministic on
purpose, because money actions must be reproducible and auditable. Then show the
per-tier accuracy table. This answer, delivered confidently, is a differentiator:
most teams cannot say where their model ends.

**"Would this survive real volume?"**
Outbox with `FOR UPDATE SKIP LOCKED` and an advisory lock, quorum queues, DLQ
with policy re-entry on replay, optimistic locking on cases, and unique
constraints carrying the caps so races cannot exceed them. Name the bottleneck
honestly: LLM latency on Tier 3, mitigated by the Redis signature cache at ~60%
hit rate.

Be precise about which constraint carries which claim, because a judge may read
the schema:

- **debit cap** → `uq_case_debit_seq` — `UNIQUE (case_id, debit_seq)` on
  `payment_attempt`. `debit_seq` counts debits only, which is what
  `max_debit_attempts` bounds.
- **no duplicate debit** → `uq_idem` — `UNIQUE (idempotency_key)`, with the key
  derived deterministically in Phase 05.
- **one intervention per ladder position** → `uq_case_attempt` —
  `UNIQUE (case_id, attempt_no)`.

`uq_case_attempt` is *not* the attempt cap and must not be described as one: a
ladder mixes debits with nudges and re-auth links, so `attempt_no` counts ladder
positions while the cap counts debits. Claiming otherwise is the kind of thing a
judge reading the DDL will catch.

**"What breaks first?"**
Payday inference. It defaults to the 1st and is only corrected by observed prior
debits on the same mandate. On real data, salary dates vary and that assumption
carries a third of the lift. First thing we'd replace with a learned per-customer
timing model.

Having a crisp answer to "what breaks first" is worth more than one more feature.

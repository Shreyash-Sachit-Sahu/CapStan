# Capstan · Phase 08 — The Cockpit (Next.js ops dashboard)

**Goal:** the surface a judge looks at for ninety seconds. It must answer three
questions without anyone explaining: *how much did it recover*, *why did it do
that*, and *where did it stop itself*.

**Done when:** three routes work against the live backend and the demo can be
driven entirely from the UI.

---

## 1. Scope — three routes, no more

```
/                       Batch view    — the number, the comparison, the ablation
/cases/[id]             Case view     — timeline, decision why-panel, audit chain
/exceptions             Exception view — the misses, grouped and owned
```

Resist a settings page, a login flow, a fourth chart. Every hour spent on chrome
is an hour not spent on Phase 05 hardening. Auth: none, it is a local demo —
state that in the README rather than half-building JWT.

---

## 2. What each route must show

### Batch view

The hero is the money, but not as a lone big number on a gradient — that reads as
templated. Make the hero the **bracket**: baseline, Capstan, and the oracle
ceiling on one horizontal scale, with the recovered rupees marked on it. It
communicates the result and its honesty in the same glance.

Below it, in order:
1. Ablation waterfall — which mechanism earned which percentage point (Phase 07 §3).
2. Cost row — wasted attempts, comms per recovery, duplicate charges avoided.
   Put Capstan's costs next to baseline's. Do not hide them below the fold.
3. Terminal breakdown — a single stacked bar: recovered / abandoned / escalated /
   expired, with escalated labelled as a *correct* outcome.
4. Guardrail activity — a small table of how many times each of G1–G11 fired.
   This is the "bounded and gated" evidence, made visible.

### Case view

Left: the intervention timeline, vertical, one row per attempt with virtual
timestamps in IST. Right: a why-panel bound to the selected row, rendering the
`DecisionRecord` — diagnosis with confidence and evidence, ladder position, every
guardrail with its ALLOW/DEFER/BLOCK result, the stop conditions, and the
`humanReadable` line.

**Show suppressed actions in the timeline**, greyed, with the guardrail that
blocked them. A timeline that shows only what happened cannot demonstrate
boundedness; one that shows the nudge that was *not* sent at 22:40 IST because of
quiet hours makes the argument by itself.

Bottom: the audit chain, with hash prefixes and a verify button that calls
`/api/ledger/verify/{caseId}` and shows a green chain or a red break.

Seed the demo with a deep link to the timeout-compensation case so it opens in one
click on stage.

### Exception view

The four groups from Phase 07 §4, with "missed" first and largest. Each row: cause,
amount, where it died, what a human should do. Make this route look deliberate,
not apologetic — it is a feature.

---

## 3. Design direction

Do not reach for the default AI-dashboard look (dark navy, one acid accent,
rounded cards, big gradient number). Ground it in the subject instead: this is an
instrument for watching money get pulled back under load, with a ratchet that
stops it slipping.

**Palette** — five values, cool and instrument-like, with one warm value reserved
exclusively for money-at-risk so the eye learns it means one thing:

```
--ink        #12161C   surfaces, near-black with blue in it
--slab       #1B222B   panels
--line       #2C3540   hairlines, gridlines
--haul       #4FD1A5   recovered (the only green in the product)
--slip       #E8A33D   at risk / wasted (the one warm value)
--halt       #C25B5B   blocked by guardrail — desaturated, not alarm-red
```

Guardrail blocks are not errors. Colouring them alarm-red tells the wrong story;
a block is the system working. Desaturated `--halt` reads as "held", which is
what it is.

**Type** — a mechanical grotesque for display (something with real character —
consider Space Grotesk or Archivo), and a tabular-figures face for every number.
Money must be monospaced and right-aligned everywhere; misaligned rupee columns
undo the impression of a financial instrument in one glance. Set
`font-variant-numeric: tabular-nums` globally on numeric cells.

**Structure** — the timeline is a sequence, so numbered attempt markers (`01`,
`02`) are earned here; use them only there, not as decoration elsewhere.

**Signature element** — the ratchet scale on the batch view. A single horizontal
track from ₹0 to total-at-risk, with baseline and Capstan as pawls on it and the
oracle ceiling as a hard stop mark. It carries the metaphor, the result, and the
honesty in one object. Spend your boldness here and keep every other panel quiet.

**Motion** — one orchestrated moment: on batch load, the pawls travel from ₹0 to
their positions over ~700ms, easing out, baseline first then Capstan. Nothing
else animates except hover states. Respect `prefers-reduced-motion`.

---

## 4. Technical notes

- Next.js App Router, Node v24, server components for the data fetches, client
  components only for the timeline selection and the verify button.
- No charting library for the ratchet scale — it is two divs and a transform.
  Use Recharts only for the ablation waterfall and the stacked bar.
- Poll `/api/backtest/run` status rather than websockets. The run takes seconds.
- All timestamps rendered in IST with an explicit `Asia/Kolkata` conversion and
  the zone shown in the label. The backend speaks UTC (Phase 01); the boundary is
  here and only here.
- Empty states are instructions, not decoration: the batch view with no run shows
  the exact curl command to start one.

---

## 5. Acceptance checks

```bash
cd frontend && npm run build && npm run start
# / renders the bracket with live numbers from the backend
# /cases/<timeout-case-id> shows CANCELLED intervention + reconcile events
# /exceptions shows the missed group non-empty and first

# Quality floor
# - responsive to 380px
# - visible keyboard focus on the timeline rows and verify button
# - prefers-reduced-motion disables the pawl animation
```

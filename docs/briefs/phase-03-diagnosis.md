# Capstan · Phase 03 — Diagnosis Layer

**Goal:** turn a messy gateway failure payload into a `FailureCause` with a
confidence score and an explicit abstention option. Deterministic first, LLM only
where determinism runs out.

**Done when:** `POST /api/cases/{id}/diagnose` returns a cause, and
`GET /api/eval/diagnosis` reports accuracy against `case_oracle.true_cause`
broken down by method.

---

## 1. Design stance

The lazy version sends every failure to an LLM. That is slower, costlier,
non-deterministic, and *less accurate* on the 85% of cases where a lookup table is
exactly right. It also makes the demo fragile.

Capstan uses a three-tier cascade:

```
raw payload
   │
   ├─► Tier 1: deterministic reason map      → 76.0% coverage, 1.00 accuracy
   │      wildcard match on (error_source, error_step, error_reason)
   │
   ├─► Tier 2: normalized narration rules    → 12.3% coverage, 1.00 accuracy
   │      regex over bank_narration; standard bank shorthand only
   │
   ├─► Tier 3: LLM classifier                → 11.0% coverage, 0.88 accuracy
   │      structured output, closed taxonomy or ABSTAIN
   │
   ├─► ABSTAIN → UNDIAGNOSED                 → 0.7%, non-retryable
   │
   └─► MandateStateRefiner (deterministic, applies to any tier's output)

Overall 0.98 on batch_v1, ceiling 0.9833, safetyFailures 0.
```

Coverage figures are measured on `batch_v1`, not estimated — see §5. The
original estimates (~72/13/15) were close enough to leave the design unchanged.

The shape of that table is the claim worth making. The deterministic tiers are
perfect on what they commit to and cover 88% of the batch; the model is handed
only the residue, where the bank declined without saying why and a free-text
narration is the sole signal. It converts 0.88 of a population that would
otherwise be 0.00. "We used AI" is not the claim — "the rules cover 88% at 1.00
and the model earns the next 11%" is, and both halves are measured.

**Tier 2 deliberately does not cover everything it could.** Its rules match
standard bank shorthand (`INSUFF BAL`, `AFA FAILED`, `A/C CLOSED`); conversational
and Hinglish phrasings (`balance nahi hai`, `bank ne mana kar diya`) are left to
Tier 3. Enumerating those in a regex file would be writing one rule per sentence,
and it would make the claim "the LLM handles what the rules cannot" quietly
false. If a colloquial pattern turns out to be common enough to be worth a rule,
it belongs in Tier 2 — but the split has to stay honest.

Report coverage and accuracy per tier. "The LLM handles the 15% the rules can't,
at 8x the accuracy of falling back to UNKNOWN" is a much stronger claim than
"we used AI."

---

## 2. The taxonomy (closed set — the LLM may not invent members)

```java
public enum FailureCause {
    INSUFFICIENT_FUNDS,
    ISSUER_DECLINE_TEMPORARY,
    NETWORK_TIMEOUT,
    DO_NOT_HONOUR,
    CARD_EXPIRED,
    MANDATE_LIMIT_EXCEEDED,
    MANDATE_REVOKED,
    MANDATE_EXPIRED,
    AUTHENTICATION_FAILED,
    ACCOUNT_CLOSED,
    RISK_BLOCKED,
    TECHNICAL_DECLINE_UNKNOWN
}
```

Each cause carries static metadata used by Phase 04 — keep it on the enum so the
policy layer cannot drift from it:

```java
public enum FailureCause {
    INSUFFICIENT_FUNDS(true,  false, "Balance shortfall at debit time"),
    ISSUER_DECLINE_TEMPORARY(true, false, "Issuer-side transient decline"),
    NETWORK_TIMEOUT(true, false, "No terminal response from gateway"),
    DO_NOT_HONOUR(true, false, "Ambiguous issuer decline"),
    CARD_EXPIRED(false, true, "Instrument past expiry"),
    MANDATE_LIMIT_EXCEEDED(false, true, "Debit above mandate per-txn cap"),
    MANDATE_REVOKED(false, false, "Customer withdrew authorisation"),
    MANDATE_EXPIRED(false, true, "Mandate validity elapsed"),
    AUTHENTICATION_FAILED(false, true, "AFA/3DS not completed"),
    ACCOUNT_CLOSED(false, false, "Underlying account no longer exists"),
    RISK_BLOCKED(false, false, "Blocked by risk controls"),
    TECHNICAL_DECLINE_UNKNOWN(true, false, "Unclassified technical decline"),
    UNDIAGNOSED(false, false, "No tier could determine a cause");

    /** May a further debit attempt on the same rail ever succeed? */
    public final boolean retryable;
    /** Does recovery require customer re-authorisation? */
    public final boolean needsReauth;
    public final String humanSummary;
}
```

`RISK_BLOCKED` being non-retryable is not an optimisation, it is a hard safety
rule. Retrying a risk-blocked debit is the behaviour the Track 02 bar calls
offense-capable. Assert it in a test.

### Why UNDIAGNOSED exists — the system must fail closed

The taxonomy originally had twelve members and abstention landed on
`TECHNICAL_DECLINE_UNKNOWN`. That was a real defect, and the eval caught it:
`safetyFailures` was 2 on the deterministic baseline.

`TECHNICAL_DECLINE_UNKNOWN` was doing two incompatible jobs. When the code map
resolves `server_error`, that is an *affirmative finding* and `retryable = true`
is correct. When the cascade abstains, that is an *absence of finding*, and
inheriting `retryable = true` from it means the system fails **open** on unknown
input — an abstention on a risk-blocked debit presents as retryable.

Crucially, no downstream branch can correct this. Phase 04 cannot "check whether
it is really risk-blocked", because if we knew that we would not have abstained.
Not knowing was being treated as permission. For money actions that has to invert.

So abstention lands on `UNDIAGNOSED`, `retryable = false`. `TECHNICAL_DECLINE_UNKNOWN`
keeps its meaning and stays reachable only by affirmative classification; no tier
may assert `UNDIAGNOSED` (`FailureCause.parseAssertable` rejects it, so a
classifier returning it is treated as a schema violation). `AbstentionSafetyTest`
asserts the property over the whole taxonomy rather than over one batch, and also
asserts that a *genuine* misread of `RISK_BLOCKED` into a retryable cause still
scores as a safety failure — the fix has to make the system safe, not the metric
blind.

---

## 3. Tier 1 — deterministic map

Ship `diagnose/rules/error_code_map.yaml`, loaded at startup and validated
(fail-fast if a mapped cause isn't in the enum).

**Key on `reason`, not `code`.** This was wrong in the first draft of this brief
and is worth stating plainly, because getting it backwards is exactly what reads
as invented. Razorpay's error object is:

```json
{"error": {
  "code": "BAD_REQUEST_ERROR",
  "description": "Authentication failed due to incorrect otp",
  "field": null,
  "source": "customer",
  "step": "payment_authentication",
  "reason": "invalid_otp",
  "metadata": {"payment_id": "pay_...", "order_id": "order_..."}
}}
```

`code` is a coarse class with three values — `BAD_REQUEST_ERROR`,
`GATEWAY_ERROR`, `SERVER_ERROR`. **`reason` carries the specific cause**, in
lowercase snake_case. `source` is one of `customer | business | bank | gateway |
razorpay` (there is no `internal`). `step` is `payment_initiation |
payment_authentication | payment_authorization | payment_capture`, and for
recurring debits it is `payment_authorization` on essentially every row, so it
carries almost no discriminating power on its own.

Razorpay publishes a specific list for **e-mandate subsequent payments**, which
is exactly this domain: `insufficient_funds`, `payment_failed`,
`payment_declined`, `bank_account_invalid`, `bank_technical_error`,
`debit_instrument_blocked`, `debit_instrument_inactive`, `mandate_not_active`,
`transaction_limit_exceeded`, `gateway_technical_error`,
`bank_account_validation_failed`, `incorrect_ifsc`.

Sources, checked at build time:
<https://razorpay.com/docs/errors/> ·
<https://razorpay.com/docs/errors/payments/list/> ·
<https://razorpay.com/docs/payments/recurring-payments/emandate/errors/>

```yaml
# key: <source>:<step>:<reason>   |  '*' wildcards allowed on any segment
- match: "customer:payment_authorization:insufficient_funds"
  cause: INSUFFICIENT_FUNDS
  confidence: 0.99
- match: "bank:payment_authorization:bank_technical_error"
  cause: ISSUER_DECLINE_TEMPORARY
  confidence: 0.95
- match: "gateway:payment_authorization:request_timed_out"
  cause: NETWORK_TIMEOUT
  confidence: 0.97
- match: "*:*:card_expired"
  cause: CARD_EXPIRED
  confidence: 0.99
- match: "*:*:transaction_limit_exceeded"
  cause: MANDATE_LIMIT_EXCEEDED
  confidence: 0.96
- match: "business:*:mandate_not_active"
  cause: MANDATE_REVOKED
  confidence: 0.99
- match: "gateway:*:payment_risk_check_failed"
  cause: RISK_BLOCKED
  confidence: 0.99
```

(Full rule set in `diagnose/rules/error_code_map.yaml`.)

### Two reasons are deliberately absent from Tier 1

`payment_failed` and `payment_declined` both mean *the bank declined and did not
tell Razorpay why*. They carry no cause information, so mapping them here would
be guessing dressed up as a rule. They fall through to Tier 2 and Tier 3, and
they are the entire reason those tiers exist.

### `mandate_not_active` is resolved after classification, not by the classifier

Razorpay reports revoked and expired mandates identically. Nothing in the six
payload fields distinguishes them — mandate validity is not in the payload — so
asking the classifier to choose would be asking it to guess, and the confusion
matrix would score that guess as model error when it is really missing input.

We hold the answer already: `mandate.valid_until < first_failed_at` means
expired, otherwise revoked. `MandateStateRefiner` applies that after any tier
returns a mandate cause. Tier 3 is left with the ambiguity that is genuinely
irreducible, which is a stronger claim than handing it one we were too lazy to
look up.

---

## 4. Tier 3 — LLM classifier

**Contract:** structured JSON, closed label set, abstention allowed, no free text
outside the schema. Temperature 0.

```
SYSTEM
You classify failed recurring-payment debits for an Indian payments merchant.
Return ONLY a JSON object, no prose, no markdown fences.

Schema:
{"cause": <one of TAXONOMY or "ABSTAIN">,
 "confidence": <0.0-1.0>,
 "evidence": "<max 20 words quoting the signal you used>"}

TAXONOMY: INSUFFICIENT_FUNDS | ISSUER_DECLINE_TEMPORARY | NETWORK_TIMEOUT |
DO_NOT_HONOUR | CARD_EXPIRED | MANDATE_LIMIT_EXCEEDED | MANDATE_REVOKED |
MANDATE_EXPIRED | AUTHENTICATION_FAILED | ACCOUNT_CLOSED | RISK_BLOCKED |
TECHNICAL_DECLINE_UNKNOWN

Rules:
- Bank narrations may be Hinglish or abbreviated. "INSUFF BAL", "balance nahi hai"
  → INSUFFICIENT_FUNDS.
- A generic decline with no distinguishing signal is DO_NOT_HONOUR, not a guess
  at the underlying reason.
- If the payload contains no usable signal, return ABSTAIN. Abstaining is correct
  behaviour, not failure.
- Never output a label outside TAXONOMY.

USER
error_code: {{code}}
error_description: {{desc}}
error_source: {{source}}
error_step: {{step}}
bank_narration: {{narration}}
rail: {{rail}}
```

Model: **`claude-haiku-4-5`**. Cheapest and fastest fit for a closed-set label
over six short fields; nothing about this task needs more. Requested via the Java
SDK's `outputConfig(Verdict.class)`, which derives the JSON schema from the record
rather than hand-rolling one.

Three properties of structured outputs the code has to account for:

- **Capitalisation is not guaranteed.** The value set is constrained, its casing
  is not. `FailureCause.parse` is case-insensitive; treating a lowercase label as
  a schema violation would show up as sporadic abstentions that look like model
  quality. The response record binds `cause` as `String` rather than a Java enum
  for the same reason — Jackson enum binding is case-sensitive.
- **A refusal or a `max_tokens` cutoff still returns 200** with content that need
  not match the schema. Both stop reasons are checked explicitly before the body
  is trusted.
- **The first request on a new schema pays grammar compilation**, cached for 24h
  afterwards. `warmUp()` spends it at startup so it does not land in the reported
  mean latency.

**Guardrails in code, not just in the prompt:**

1. Parse strictly. A label outside the enum → treat as `ABSTAIN`, log it, count it
   as a schema violation in the eval report. (Do not silently coerce.)
2. Strip markdown fences before parsing. Structured outputs make this rare rather
   than impossible, and it is the same path a refusal or truncation lands on:
   `text.replaceAll("(?s)^```(json)?|```$", "")`.
3. 12s timeout, 2 retries with jitter, then `ABSTAIN`. The pipeline must never
   block on the model.
4. Cache by hash of the payload signature in Redis (`diag:v1:<sha256>`), 24h TTL.
   Identical failures are extremely common in a batch; this cuts LLM calls by
   roughly 60% on a 300-case run and makes the demo fast.
5. **Never** send the customer's contact details or the oracle to the model. Only
   the six fields above.

`ABSTAIN` maps to `TECHNICAL_DECLINE_UNKNOWN` with `confidence = 0.0` and
`diagnosis_method = ABSTAIN`. Phase 04 routes zero-confidence cases to a
conservative branch rather than guessing.

---

## 5. Eval harness

`GET /api/eval/diagnosis?batch=v1` runs diagnosis over the batch and joins to
`case_oracle.true_cause` (this endpoint lives in `dev.capstan.backtest`, the only
package permitted to touch the oracle).

Report:

- overall accuracy
- accuracy and coverage **per tier** (this is the interesting table)
- confusion matrix, 12x12, rendered in Phase 08
- abstention rate and accuracy-on-non-abstained
- schema-violation count
- mean LLM latency and cache hit rate

Target: ≥ 0.93 overall, with Tier 1 ≈ 0.99 and Tier 3 ≥ 0.75. If Tier 3 lands
below 0.70, the fix is a better closed-set prompt and more narration examples, not
a bigger model.

**Measured on `batch_v1`** (gemini-flash-lite-latest, free tier):

| Metric | Value |
|---|---|
| overall | **0.98** (ceiling 0.9833) |
| Tier 1 CODE_MAP | 228 cases, 76.0% coverage, 1.00 accuracy |
| Tier 2 NARRATION | 37 cases, 12.3% coverage, 1.00 accuracy |
| Tier 3 LLM | 33 cases, 11.0% coverage, 0.88 accuracy |
| ABSTAIN | 2 cases, 0.7% |
| safetyFailures | **0** |
| severity-weighted error rate | 0.0027 |
| schema violations | 2 |
| mean LLM latency | 7.07 s |
| cache hit rate | 14.3% (30 calls for 35 cases) |

Six errors in 300, against five cases that are undecidable from the payload — so
the cascade is one case off the achievable maximum. The remaining confusions are
all conservative: four collapse into `DO_NOT_HONOUR`, which is what an
undisclosed decline looks like, and two abstain.

The **14.3% cache hit rate** is worth recording because §4 predicted ~60%. That
estimate assumed repeated identical failures; in practice the bank narration
varies enough that most payload signatures are unique. The cache is still worth
having — it makes the demo instant and makes a re-run after a quota interruption
resume rather than restart — but it is not a 60% cost saving and the brief should
not claim one.

**Report the errors that cost money differently from the ones that don't.** A
`DO_NOT_HONOUR` misread as `INSUFFICIENT_FUNDS` costs one wasted retry. A
`RISK_BLOCKED` misread as anything retryable is a safety failure. Weight the
confusion matrix cells accordingly and show a "severity-weighted error rate"
alongside raw accuracy — this is exactly the kind of honesty the buildathon bars
keep asking for.

---

## 6. Acceptance checks

Two gates, measured separately. The first needs no credentials, which is the
point: a missing or broken API key must never block the phase.

**Gate 1 — deterministic baseline (Tier 3 stubbed to ABSTAIN).**

```bash
curl -s localhost:8080/api/eval/diagnosis?batch=v1 | jq '.overall, .perTier'
# expect: Tier 1 and Tier 2 accuracy == 1.00, abstentions == the Tier 3 population
# overall will NOT reach 0.93 here, by construction — the obscured population abstains
```

**Gate 2 — full cascade (`ANTHROPIC_API_KEY` set).**

```bash
curl -s localhost:8080/api/eval/diagnosis?batch=v1 | jq '.overall, .perTier, .runtime'
# expect overall >= 0.93, runtime.llmEnabled == true
```

Only gate 2 is measured against 0.93. Note the achievable ceiling is below 1.00
and is stated in the batch manifest as `accuracy_ceiling`: some obscured payloads
carry a generic reason *and* a narration that says nothing, so no tier can
recover them. Reporting accuracy without that ceiling next to it would imply a
target that does not exist.

```bash
./mvnw test -Dtest=DiagnosisRulesTest
# RiskBlockedNeverRetryable, LlmSchemaViolationBecomesAbstain,
# MarkdownFenceStripping, MandateStateRefiner, and rule-file validation
```

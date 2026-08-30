# Corrected semantics: what completes a mandate re-authorisation

**Written 2026-08-30, before the re-run. No post-fix numbers existed when this
was committed.**

## The defect

`SimulatedGateway.railPermits` currently reads:

```java
case "REAUTH_REQUIRED" -> !commsFor(cmd.caseId()).isEmpty();
```

Any message at all makes a `REAUTH_REQUIRED` case chargeable. The fixed-ladder
baseline sends one generic *"Your payment did not go through. Please ensure funds
are available."* SMS after its second failure, and from that point the simulator
treats the customer's mandate as re-authorised.

That is false about the world. A dunning notice tells someone a payment failed.
Re-authorising an e-mandate means the customer completing an authenticated flow
with their bank — a new UMN, a fresh AFA, a re-registered standing instruction.
The two are not the same event and one does not imply the other.

## Corrected rule

A `REAUTH_REQUIRED` case becomes chargeable only when **a `REAUTH_LINK`
intervention has been sent**, and then only if the customer acted on it — which
the oracle already models as `nudge_sensitivity`, and which the gateway already
applies as the success probability for this channel. A generic nudge, a dunning
SMS, or any other message never satisfies it.

```java
case "REAUTH_REQUIRED" -> commsFor(cmd.caseId()).stream()
        .anyMatch(c -> "REAUTH_LINK".equals(c.kind()));
```

## Why this is a defect fix and not a re-scoring

A cost model is a *scoring function*: a choice about how to weigh outcomes, where
there is no fact of the matter and any weighting is defensible. Picking one after
seeing results is unfalsifiable, which is why we refused to add one and still
refuse.

This is a *factual error about the payments domain*. It would be wrong in the same
direction regardless of which arm it happened to favour. The test we applied is
"would we fix this if it hurt us" — yes, because a simulator in which an SMS
completes a re-authorisation is not measuring anything real. It is the same
category as the two harness bugs already fixed in this phase (cases decided before
they failed; G11 contaminated across batches), both of which flattered the
baseline and both of which were fixed anyway, at a measured cost of ~22pp to
Capstan.

## Predicted direction, recorded before measuring

`REAUTH_REQUIRED` is 64 cases and 25.0% of at-risk value on `batch_holdout`.

- **The baseline should lose most of that access.** It reaches those cases today
  only through the defect.
- **Capstan should be unchanged.** Its `REAUTH_REQUIRED` ladders
  (`CARD_EXPIRED`, `MANDATE_EXPIRED`, `MANDATE_LIMIT_EXCEEDED`) carry
  `max_debit_attempts: 0` and contain no debit rung, so it never charged those
  cases before the fix and will not after it.
- **Net: the gap should narrow, and possibly invert.** Both arms lose access to
  the same value; the baseline loses more because it was the only one taking it.

If the measured direction contradicts this, that is itself a finding and gets
reported.

## Explicitly not changed in this commit

No debit rung is added after `REAUTH_LINK`. Capstan structurally cannot recover a
quarter of the at-risk value, and whether that path is worth building is a
separate question to answer *after* the re-run, on evidence about how often a
re-auth link actually converts. Bundling a policy improvement into a simulator
correction would make both unreadable.

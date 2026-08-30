package dev.capstan.policy;

import dev.capstan.diagnose.FailureCause;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * G1-G12, in the order they decide. All twelve are evaluated on every decision;
 * the first non-ALLOW in this order wins.
 */
public final class Guardrails {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime QUIET_START = LocalTime.of(21, 0);
    static final LocalTime QUIET_END = LocalTime.of(9, 0);
    static final LocalTime RESUME_AT = LocalTime.of(9, 15);

    static final Duration COOLING_OFF = Duration.ofHours(4);
    static final Duration COMMS_MIN_GAP = Duration.ofHours(48);
    static final int COMMS_MAX_PER_CYCLE = 3;

    /** G11 needs a floor, or one failed attempt out of one is a 100% failure rate. */
    static final int ISSUER_BREAKER_MIN_SAMPLE = 4;
    static final double ISSUER_BREAKER_THRESHOLD = 0.25;
    static final Duration ISSUER_BREAKER_HOLD = Duration.ofHours(1);

    private Guardrails() {
    }

    private record Rule(String id, String name, Evaluator evaluator) implements Guardrail {
        @Override
        public Guardrail.Outcome evaluate(DecisionContext ctx, InterventionKind proposed,
                                          PolicyTable.Policy policy) {
            return evaluator.evaluate(ctx, proposed, policy);
        }
    }

    @FunctionalInterface
    private interface Evaluator {
        Guardrail.Outcome evaluate(DecisionContext ctx, InterventionKind proposed,
                                   PolicyTable.Policy policy);
    }

    public static List<Guardrail> all() {
        return List.of(
                new Rule("G1", "Opt-out", Guardrails::optOut),
                new Rule("G2", "Cycle boundary", Guardrails::cycleBoundary),
                new Rule("G3", "Mandate validity", Guardrails::mandateValidity),
                new Rule("G4", "Mandate cap", Guardrails::mandateCap),
                new Rule("G5", "Attempt cap", Guardrails::attemptCap),
                new Rule("G6", "Cooling-off", Guardrails::coolingOff),
                new Rule("G7", "Quiet hours", Guardrails::quietHours),
                new Rule("G8", "Comms frequency", Guardrails::commsFrequency),
                new Rule("G9", "Rail availability", Guardrails::railAvailability),
                new Rule("G10", "Risk hold", Guardrails::riskHold),
                new Rule("G11", "Issuer circuit breaker", Guardrails::issuerBreaker),
                new Rule("G12", "Re-authorisation pending", Guardrails::reauthPending));
    }

    // G1 — debits are contractual, not marketing, so an opt-out does not stop them.
    private static Guardrail.Outcome optOut(DecisionContext ctx, InterventionKind proposed,
                                            PolicyTable.Policy policy) {
        if (!proposed.isComms) {
            return Guardrail.Outcome.notApplicable("not a comms action");
        }
        return ctx.contactOptedOut()
                ? Guardrail.Outcome.advanceLadder("customer opted out of contact")
                : Guardrail.Outcome.allow("customer accepts contact");
    }

    // G2 — the hard stop. Nothing happens after the cycle closes.
    private static Guardrail.Outcome cycleBoundary(DecisionContext ctx, InterventionKind proposed,
                                                   PolicyTable.Policy policy) {
        if (ctx.billingCycleEnd() == null) {
            return Guardrail.Outcome.terminate("EXPIRED", "billing cycle end unknown; refusing to act");
        }
        return ctx.pastCycleEnd()
                ? Guardrail.Outcome.terminate("EXPIRED", "now is past billing_cycle_end")
                : Guardrail.Outcome.allow("within billing cycle until " + ctx.billingCycleEnd());
    }

    private static Guardrail.Outcome mandateValidity(DecisionContext ctx, InterventionKind proposed,
                                                     PolicyTable.Policy policy) {
        if (!proposed.isDebit) {
            return Guardrail.Outcome.notApplicable("not a debit action");
        }
        if (!ctx.mandateIsActive()) {
            return Guardrail.Outcome.advanceLadder(
                    "mandate status is " + (ctx.mandateStatus() == null ? "unknown" : ctx.mandateStatus()));
        }
        if (!ctx.withinMandateValidity()) {
            return Guardrail.Outcome.advanceLadder("now is outside valid_from..valid_until");
        }
        return Guardrail.Outcome.allow("mandate ACTIVE and within validity");
    }

    // G4 — the one substitution rule: an over-cap debit becomes a re-auth request.
    private static Guardrail.Outcome mandateCap(DecisionContext ctx, InterventionKind proposed,
                                                PolicyTable.Policy policy) {
        if (!proposed.isDebit) {
            return Guardrail.Outcome.notApplicable("not a debit action");
        }
        if (ctx.mandateMaxAmountPaise() == null) {
            return Guardrail.Outcome.substitute(InterventionKind.REAUTH_LINK,
                    "mandate cap unknown; cannot confirm the debit is within it");
        }
        return ctx.withinMandateCap()
                ? Guardrail.Outcome.allow(ctx.amountPaise() + " within cap " + ctx.mandateMaxAmountPaise())
                : Guardrail.Outcome.substitute(InterventionKind.REAUTH_LINK,
                        ctx.amountPaise() + " exceeds mandate cap " + ctx.mandateMaxAmountPaise());
    }

    private static Guardrail.Outcome attemptCap(DecisionContext ctx, InterventionKind proposed,
                                                PolicyTable.Policy policy) {
        if (!proposed.isDebit) {
            return Guardrail.Outcome.notApplicable("not a debit action");
        }
        String tally = ctx.debitAttemptsMade() + "/" + policy.maxDebitAttempts();
        return ctx.debitAttemptsMade() >= policy.maxDebitAttempts()
                ? Guardrail.Outcome.advanceLadder("debit attempts exhausted " + tally)
                : Guardrail.Outcome.allow("attempts " + tally);
    }

    private static Guardrail.Outcome coolingOff(DecisionContext ctx, InterventionKind proposed,
                                                PolicyTable.Policy policy) {
        if (!proposed.isDebit) {
            return Guardrail.Outcome.notApplicable("not a debit action");
        }
        if (ctx.lastDebitAttemptAt() == null) {
            return Guardrail.Outcome.allow("no previous debit attempt");
        }
        Instant readyAt = ctx.lastDebitAttemptAt().plus(COOLING_OFF);
        if (ctx.now().isBefore(readyAt)) {
            return Guardrail.Outcome.defer(readyAt, "last debit attempt was under 4h ago");
        }
        return Guardrail.Outcome.allow("last attempt "
                + Duration.between(ctx.lastDebitAttemptAt(), ctx.now()).toHours() + "h ago");
    }

    // G7 — converts explicitly. The JVM is pinned to UTC on purpose (Phase 01),
    // so the IST conversion has to be written out rather than inherited.
    private static Guardrail.Outcome quietHours(DecisionContext ctx, InterventionKind proposed,
                                                PolicyTable.Policy policy) {
        if (!proposed.isComms) {
            return Guardrail.Outcome.notApplicable("not a comms action");
        }
        if (!isQuiet(ctx.now())) {
            return Guardrail.Outcome.allow("outside quiet hours in IST");
        }
        return Guardrail.Outcome.defer(nextResumeInstant(ctx.now()),
                "quiet hours 21:00-09:00 IST; holding until 09:15 IST");
    }

    static boolean isQuiet(Instant instant) {
        LocalTime ist = instant.atZone(IST).toLocalTime();
        return ist.isBefore(QUIET_END) || !ist.isBefore(QUIET_START);
    }

    static Instant nextResumeInstant(Instant now) {
        ZonedDateTime ist = now.atZone(IST);
        ZonedDateTime resume = ist.with(RESUME_AT);
        if (!resume.isAfter(ist)) {
            resume = resume.plusDays(1);
        }
        return resume.toInstant();
    }

    private static Guardrail.Outcome commsFrequency(DecisionContext ctx, InterventionKind proposed,
                                                    PolicyTable.Policy policy) {
        if (!proposed.isComms) {
            return Guardrail.Outcome.notApplicable("not a comms action");
        }
        if (ctx.lastCommsAt() != null
                && Duration.between(ctx.lastCommsAt(), ctx.now()).compareTo(COMMS_MIN_GAP) < 0) {
            return Guardrail.Outcome.advanceLadder("a message was sent within the last 48h");
        }
        if (ctx.commsSentInCycle() >= COMMS_MAX_PER_CYCLE) {
            return Guardrail.Outcome.advanceLadder(
                    ctx.commsSentInCycle() + " messages already sent this cycle (max "
                            + COMMS_MAX_PER_CYCLE + ")");
        }
        if (policy.maxNudges() != null && ctx.commsSentInCycle() >= policy.maxNudges()) {
            return Guardrail.Outcome.advanceLadder("policy caps nudges at " + policy.maxNudges());
        }
        return Guardrail.Outcome.allow(ctx.commsSentInCycle() + " messages sent this cycle");
    }

    private static Guardrail.Outcome railAvailability(DecisionContext ctx, InterventionKind proposed,
                                                      PolicyTable.Policy policy) {
        if (proposed != InterventionKind.RAIL_SWITCH) {
            return Guardrail.Outcome.notApplicable("not a rail switch");
        }
        return ctx.alternateRail() == null || ctx.alternateRail().isBlank()
                ? Guardrail.Outcome.advanceLadder("no alternate rail on file")
                : Guardrail.Outcome.allow("alternate rail " + ctx.alternateRail() + " available");
    }

    // G10 — the hard safety stop. Either trigger routes everything to a human.
    private static Guardrail.Outcome riskHold(DecisionContext ctx, InterventionKind proposed,
                                              PolicyTable.Policy policy) {
        boolean byCause = ctx.cause() == FailureCause.RISK_BLOCKED;
        boolean byFlag = Boolean.TRUE.equals(ctx.riskFlagged());
        if (!byCause && !byFlag) {
            return Guardrail.Outcome.allow("no risk hold on this case or customer");
        }
        if (proposed == InterventionKind.HUMAN_ESCALATION) {
            return Guardrail.Outcome.allow("escalation is the only permitted action under a risk hold");
        }
        String why = byCause && byFlag ? "cause is RISK_BLOCKED and the customer is risk-flagged"
                : byCause ? "cause is RISK_BLOCKED" : "customer is risk-flagged";
        return Guardrail.Outcome.substitute(InterventionKind.HUMAN_ESCALATION, why);
    }

    /**
     * G11 — if an issuer is having a bad hour, hammering it makes recovery worse
     * for every case behind us.
     *
     * <p>Grouped by failure reason, not by issuer: Razorpay's payload carries no
     * issuer identifier, and inventing a column would be worse than naming the
     * approximation. On real data with issuer IDs you would group by those.
     */
    private static Guardrail.Outcome issuerBreaker(DecisionContext ctx, InterventionKind proposed,
                                                   PolicyTable.Policy policy) {
        if (!proposed.isDebit) {
            return Guardrail.Outcome.notApplicable("not a debit action");
        }
        if (ctx.recentAttemptsSameReason() < ISSUER_BREAKER_MIN_SAMPLE) {
            return Guardrail.Outcome.allow("only " + ctx.recentAttemptsSameReason()
                    + " recent attempts for reason '" + ctx.failureReason() + "'; below sample floor");
        }
        double failureRate = (double) ctx.recentFailuresSameReason() / ctx.recentAttemptsSameReason();
        if (failureRate > ISSUER_BREAKER_THRESHOLD) {
            return Guardrail.Outcome.defer(ctx.now().plus(ISSUER_BREAKER_HOLD),
                    Math.round(failureRate * 100) + "% of recent attempts for reason '"
                            + ctx.failureReason() + "' failed; holding debits 1h");
        }
        return Guardrail.Outcome.allow(Math.round(failureRate * 100)
                + "% recent failure rate for reason '" + ctx.failureReason() + "'");
    }

    /**
     * G12 -- a debit waits for a re-authorisation to be completed, not merely
     * requested.
     *
     * <p>Four causes now carry one debit rung after their REAUTH_LINK, because a
     * customer who re-registers their mandate should be charged rather than
     * escalated to a human. That rung is only safe if the policy can tell the
     * difference between "we asked" and "they did it" -- charging on the strength
     * of having sent a link is guessing, which is what the guardrails exist to
     * stop.
     *
     * <p>Completion is observed: the gateway resolves it when the link is sent and
     * { DebitWorker} persists it. Not applicable where no link was sent.
     */
    private static Guardrail.Outcome reauthPending(DecisionContext ctx, InterventionKind proposed,
                                                   PolicyTable.Policy policy) {
        if (!proposed.isDebit || ctx.reauthRequestedAt() == null) {
            return Guardrail.Outcome.notApplicable("no re-authorisation outstanding");
        }
        if (ctx.reauthCompletedAt() != null) {
            return Guardrail.Outcome.allow("re-authorisation completed at " + ctx.reauthCompletedAt());
        }
        return Guardrail.Outcome.advanceLadder(
                "re-authorisation requested at " + ctx.reauthRequestedAt()
                        + " but not completed; no debit until the customer acts");
    }
}

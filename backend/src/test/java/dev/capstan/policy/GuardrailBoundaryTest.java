package dev.capstan.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.diagnose.FailureCause;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Boundaries, where off-by-one errors actually live. IST is UTC+5:30, so every
 * instant here is written as UTC and asserted in IST — which is the whole point:
 * the JVM is pinned to UTC (Phase 01) and the conversion has to be explicit.
 */
class GuardrailBoundaryTest {

    private final PolicyTable table = new PolicyTable();
    private final PolicyEngine engine = new PolicyEngine(table);

    private static Instant ist(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal),
                ZoneId.of("Asia/Kolkata")).toInstant();
    }

    @Test
    void quietHoursBoundariesAreExact() {
        assertThat(Guardrails.isQuiet(ist("2026-09-03T20:59:59"))).as("20:59:59 IST").isFalse();
        assertThat(Guardrails.isQuiet(ist("2026-09-03T21:00:00"))).as("21:00:00 IST").isTrue();
        assertThat(Guardrails.isQuiet(ist("2026-09-03T08:59:59"))).as("08:59:59 IST").isTrue();
        assertThat(Guardrails.isQuiet(ist("2026-09-03T09:00:00"))).as("09:00:00 IST").isFalse();
    }

    @Test
    void anInstantOnAnEarlierUtcDateIsStillJudgedInIst() {
        // 00:30 IST on 4 Sep is 19:00 UTC on 3 Sep. Judging this in UTC would call
        // it early evening and send a message at half past midnight.
        Instant justAfterMidnightIst = ist("2026-09-04T00:30:00");

        assertThat(justAfterMidnightIst.toString()).startsWith("2026-09-03");
        assertThat(Guardrails.isQuiet(justAfterMidnightIst)).isTrue();
    }

    @Test
    void quietHoursDeferToThe0915IstResumeTime() {
        Instant resume = Guardrails.nextResumeInstant(ist("2026-09-03T22:40:00"));
        ZonedDateTime inIst = resume.atZone(ZoneId.of("Asia/Kolkata"));

        assertThat(inIst.getHour()).isEqualTo(9);
        assertThat(inIst.getMinute()).isEqualTo(15);
        assertThat(inIst.toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 4));
    }

    @Test
    void debitsAreExemptFromQuietHours() {
        // Machine-to-machine. A contractual debit at 02:00 IST bothers nobody.
        DecisionRecord record = engine.decide(Ctx.a()
                .cause(FailureCause.NETWORK_TIMEOUT).ladderPosition(1)
                .now(ist("2026-09-03T02:00:00")).build());

        assertThat(record.finalAction()).isEqualTo(InterventionKind.IMMEDIATE_RETRY);
    }

    @Test
    void coolingOffBoundaryIsFourHours() {
        Instant lastAttempt = Instant.parse("2026-09-03T04:00:00Z");

        DecisionRecord justInside = engine.decide(Ctx.a()
                .cause(FailureCause.NETWORK_TIMEOUT).ladderPosition(1)
                .lastDebitAt(lastAttempt).now(lastAttempt.plusSeconds(4 * 3600 - 1)).build());
        assertThat(justInside.guardrails())
                .anyMatch(g -> g.id().equals("G6") && g.result().equals("DEFER"));

        DecisionRecord justOutside = engine.decide(Ctx.a()
                .cause(FailureCause.NETWORK_TIMEOUT).ladderPosition(1)
                .lastDebitAt(lastAttempt).now(lastAttempt.plusSeconds(4 * 3600)).build());
        assertThat(justOutside.finalAction()).isEqualTo(InterventionKind.IMMEDIATE_RETRY);
    }

    @Test
    void cycleBoundaryTerminatesRatherThanActing() {
        Instant cycleEnd = Instant.parse("2026-10-06T00:00:00Z");
        DecisionRecord record = engine.decide(Ctx.a()
                .cycleEnd(cycleEnd).now(cycleEnd.plusSeconds(1)).build());

        assertThat(record.terminalStatus()).isEqualTo("EXPIRED");
        assertThat(record.terminalReason()).isNotBlank();
    }

    @Test
    void anUnknownCycleEndRefusesToAct() {
        // Absence of a boundary is not absence of a limit.
        DecisionRecord record = engine.decide(Ctx.a().cycleEnd(null).build());
        assertThat(record.terminalStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void anOverCapDebitBecomesAReauthRequestRatherThanBeingDropped() {
        DecisionRecord record = engine.decide(Ctx.a()
                .cause(FailureCause.INSUFFICIENT_FUNDS)
                .amountPaise(500_000).maxAmountPaise(100_000L).build());

        assertThat(record.proposedAction()).isEqualTo(InterventionKind.PAYDAY_RETRY);
        assertThat(record.finalAction()).isEqualTo(InterventionKind.REAUTH_LINK);
        assertThat(record.guardrails())
                .anyMatch(g -> g.id().equals("G4") && g.result().equals("BLOCK"));
    }

    @Test
    void aSubstitutedActionIsItselfGuardrailChecked() {
        // G4 substitutes a re-auth link, which is comms -- so opt-out must still
        // apply to it. Substitution must not be an escape hatch.
        DecisionRecord record = engine.decide(Ctx.a()
                .cause(FailureCause.INSUFFICIENT_FUNDS)
                .amountPaise(500_000).maxAmountPaise(100_000L)
                .optedOut(true).build());

        assertThat(record.finalAction()).isNotEqualTo(InterventionKind.REAUTH_LINK);
    }

    @Test
    void railSwitchWithoutAnAlternateAdvancesInsteadOfFailing() {
        DecisionRecord record = engine.decide(Ctx.a()
                .cause(FailureCause.AUTHENTICATION_FAILED).alternateRail(null).build());

        assertThat(record.finalAction()).isNotEqualTo(InterventionKind.RAIL_SWITCH);
        assertThat(record.guardrails())
                .anyMatch(g -> g.id().equals("G9") && g.result().equals("BLOCK"));
    }

    @Test
    void issuerBreakerNeedsASampleFloorBeforeItFires() {
        // 1 failure out of 1 attempt is 100% and means nothing.
        DecisionRecord thin = engine.decide(Ctx.a()
                .cause(FailureCause.NETWORK_TIMEOUT).ladderPosition(1).recent(1, 1).build());
        assertThat(thin.finalAction()).isEqualTo(InterventionKind.IMMEDIATE_RETRY);

        DecisionRecord loud = engine.decide(Ctx.a()
                .cause(FailureCause.NETWORK_TIMEOUT).ladderPosition(1).recent(20, 12).build());
        assertThat(loud.guardrails())
                .anyMatch(g -> g.id().equals("G11") && g.result().equals("DEFER"));
    }

    @Test
    void commsFrequencyBlocksASecondMessageInsideFortyEightHours() {
        // Deliberately mid-afternoon IST: quiet hours (G7) is evaluated before
        // comms frequency (G8), and would defer rather than block, masking this.
        Instant now = ist("2026-09-05T13:30:00");
        DecisionRecord record = engine.decide(Ctx.a()
                .cause(FailureCause.CARD_EXPIRED).ladderPosition(1)
                .lastCommsAt(now.minusSeconds(47 * 3600)).now(now).build());

        assertThat(record.finalAction()).isNotEqualTo(InterventionKind.CUSTOMER_NUDGE);
        assertThat(record.guardrails())
                .anyMatch(g -> g.id().equals("G8") && g.result().equals("BLOCK"));
    }

    @Test
    void anOptedOutCustomerStillGetsDebitedBecauseDebitsAreContractual() {
        DecisionRecord record = engine.decide(Ctx.a().optedOut(true).salaryDay(1).build());
        assertThat(record.finalAction()).isEqualTo(InterventionKind.PAYDAY_RETRY);
    }
}

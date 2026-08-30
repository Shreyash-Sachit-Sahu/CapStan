package dev.capstan.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private final PolicyTable table = new PolicyTable();
    private final PolicyEngine engine = new PolicyEngine(table);

    private static String resultOf(DecisionRecord record, String guardrailId) {
        return record.guardrails().stream()
                .filter(g -> g.id().equals(guardrailId))
                .reduce((first, second) -> second)   // last evaluation of that guardrail
                .orElseThrow()
                .result();
    }

    @Nested
    @DisplayName("PolicyLadder")
    class PolicyLadderTest {

        @Test
        void everyCauseReachesATerminalStateWithAStatedReason() {
            // Asserted by behaviour, not by shape. NETWORK_TIMEOUT's ladder has no
            // explicit terminal rung; exhausting it abandons the case, which is
            // equally terminal. What must hold for every cause is that the case
            // ends and says why.
            for (FailureCause cause : FailureCause.values()) {
                if (cause == FailureCause.UNDIAGNOSED) {
                    continue;   // handled by the clearance branch, not the table
                }
                PolicyTable.Policy policy = table.forCause(cause);
                assertThat(policy.ladder()).as("%s ladder", cause).isNotEmpty();

                DecisionRecord exhausted = engine.decide(Ctx.a()
                        .cause(cause).ladderPosition(policy.ladder().size()).build());

                assertThat(exhausted.isTerminal()).as("%s did not terminate", cause).isTrue();
                assertThat(exhausted.terminalReason())
                        .as("%s terminated without a reason", cause).isNotBlank();
                assertThat(exhausted.finalAction().isDebit)
                        .as("%s authorised a debit at ladder exhaustion", cause).isFalse();
            }
        }

        @Test
        void insufficientFundsWaitsForPaydayRatherThanRetryingNow() {
            DecisionRecord record = engine.decide(Ctx.a().salaryDay(1).build());

            assertThat(record.finalAction()).isEqualTo(InterventionKind.PAYDAY_RETRY);
            assertThat(record.scheduledFor()).isAfter(Ctx.NOW);
            assertThat(record.humanReadable()).contains("Waiting for the salary window");
        }

        @Test
        void networkTimeoutReconcilesBeforeItRetries() {
            DecisionRecord record = engine.decide(
                    Ctx.a().cause(FailureCause.NETWORK_TIMEOUT).build());
            assertThat(record.finalAction()).isEqualTo(InterventionKind.RECONCILE_ONLY);
        }

        /**
         * MANDATE_EXPIRED now permits exactly one debit, but only after the
         * customer has completed a re-authorisation. This asserts the property
         * that actually matters -- no debit on the strength of having *sent* a
         * link -- rather than the old blanket "never debits", which stopped being
         * true when the rung was added and would otherwise just get deleted.
         */
        @Test
        void mandateExpiredNeverDebitsWithoutACompletedReauth() {
            PolicyTable.Policy policy = table.forCause(FailureCause.MANDATE_EXPIRED);
            assertThat(policy.maxDebitAttempts()).isOne();
            assertThat(policy.ladder().get(0)).isEqualTo(InterventionKind.REAUTH_LINK);

            DecisionRecord asked = engine.decide(Ctx.a()
                    .cause(FailureCause.MANDATE_EXPIRED).ladderPosition(1)
                    .reauthRequestedAt(Instant.parse("2026-09-01T10:00:00Z"))
                    .reauthCompletedAt(null).build());
            assertThat(asked.finalAction().isDebit)
                    .as("a link that was sent but not completed must not authorise a debit")
                    .isFalse();
            assertThat(asked.guardrails())
                    .anyMatch(g -> g.id().equals("G12") && g.result().equals("BLOCK"));

            DecisionRecord completed = engine.decide(Ctx.a()
                    .cause(FailureCause.MANDATE_EXPIRED).ladderPosition(1)
                    .reauthRequestedAt(Instant.parse("2026-09-01T10:00:00Z"))
                    .reauthCompletedAt(Instant.parse("2026-09-01T16:00:00Z")).build());
            assertThat(completed.finalAction())
                    .as("a completed re-authorisation earns exactly one debit")
                    .isEqualTo(InterventionKind.SCHEDULED_RETRY);
        }

        @Test
        void exhaustedLadderAbandonsWithAReason() {
            DecisionRecord record = engine.decide(Ctx.a().ladderPosition(99).build());
            assertThat(record.finalAction()).isEqualTo(InterventionKind.ABANDON);
            assertThat(record.terminalReason()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("RiskBlockedHardStop")
    class RiskBlockedHardStopTest {

        @Test
        void riskBlockedCauseProducesOnlyEscalation() {
            DecisionRecord record = engine.decide(
                    Ctx.a().cause(FailureCause.RISK_BLOCKED).build());

            assertThat(record.finalAction()).isEqualTo(InterventionKind.HUMAN_ESCALATION);
            assertThat(record.finalAction().isDebit).isFalse();
        }

        @Test
        void aRiskFlaggedCustomerCannotBeDebitedEvenOnARecoverableCause() {
            // The honest cost of failing closed: this case would have recovered.
            DecisionRecord record = engine.decide(
                    Ctx.a().cause(FailureCause.INSUFFICIENT_FUNDS).riskFlagged(true).build());

            assertThat(record.finalAction()).isEqualTo(InterventionKind.HUMAN_ESCALATION);
            assertThat(resultOf(record, "G10")).isEqualTo("ALLOW");   // allows only the escalation
            assertThat(record.guardrails())
                    .anyMatch(g -> g.id().equals("G10") && g.detail().contains("risk-flagged"));
        }

        @Test
        void theTerminalReasonNamesTheGuardrailThatStoppedIt() {
            // Without this the record inherits the ladder's exhaustion reason and
            // claims the balance never arrived, which is not what stopped the case.
            // Phase 07's exception list would then group it under the wrong heading.
            DecisionRecord record = engine.decide(
                    Ctx.a().cause(FailureCause.INSUFFICIENT_FUNDS).riskFlagged(true).build());

            assertThat(record.terminalReason())
                    .contains("G10")
                    .contains("risk-flagged")
                    .doesNotContain("Balance never arrived");
        }

        @Test
        void noRiskBlockedCaseEverYieldsADebitAtAnyLadderPosition() {
            for (int position = 0; position < 6; position++) {
                DecisionRecord record = engine.decide(Ctx.a()
                        .cause(FailureCause.RISK_BLOCKED).ladderPosition(position).build());
                assertThat(record.finalAction().isDebit)
                        .as("position %s produced %s", position, record.finalAction())
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("LowConfidenceOverride")
    class LowConfidenceOverrideTest {

        @Test
        void lowConfidenceIgnoresTheAggressiveLadder() {
            DecisionRecord record = engine.decide(Ctx.a()
                    .cause(FailureCause.INSUFFICIENT_FUNDS).confidence(0.4).build());

            assertThat(record.policyName()).isEqualTo("low_confidence");
            assertThat(record.finalAction()).isEqualTo(InterventionKind.SCHEDULED_RETRY);
            assertThat(record.stopConditions()).contains("max_debit_attempts=1");
        }

        @Test
        void confidenceAtTheFloorStillUsesTheCauseLadder() {
            DecisionRecord record = engine.decide(Ctx.a().confidence(0.60).build());
            assertThat(record.policyName()).isEqualTo("INSUFFICIENT_FUNDS");
        }
    }

    @Nested
    @DisplayName("UndiagnosedClearance")
    class UndiagnosedClearanceTest {

        private Ctx undiagnosed() {
            return Ctx.a()
                    .cause(FailureCause.UNDIAGNOSED)
                    .confidence(0.0)
                    .method(Diagnosis.DiagnosisMethod.ABSTAIN);
        }

        @Test
        void everyCheckClearingPermitsExactlyOneScheduledRetry() {
            DecisionRecord record = engine.decide(undiagnosed().build());

            assertThat(record.policyName()).isEqualTo("undiagnosed_cleared");
            assertThat(record.finalAction()).isEqualTo(InterventionKind.SCHEDULED_RETRY);
            assertThat(record.stopConditions()).contains("max_debit_attempts=1");
            assertThat(record.clearanceChecks()).hasSize(4)
                    .allMatch(DecisionRecord.ClearanceCheck::cleared);
        }

        @Test
        void anInactiveMandateBlocksTheDebit() {
            DecisionRecord record = engine.decide(undiagnosed().mandateStatus("REVOKED").build());
            assertThat(record.finalAction()).isEqualTo(InterventionKind.HUMAN_ESCALATION);
            assertThat(record.policyName()).isEqualTo("undiagnosed_uncleared");
        }

        @Test
        void anUnavailableRiskFlagIsNotAClearance() {
            // The load-bearing case: null is not false.
            DecisionRecord record = engine.decide(undiagnosed().riskFlagged(null).build());

            assertThat(record.finalAction()).isEqualTo(InterventionKind.HUMAN_ESCALATION);
            assertThat(record.clearanceChecks())
                    .anyMatch(c -> c.name().equals("no_risk_flag")
                            && !c.cleared() && c.detail().contains("unavailable"));
        }

        @Test
        void anUnavailableMandateCapIsNotAClearance() {
            DecisionRecord record = engine.decide(undiagnosed().maxAmountPaise(null).build());
            assertThat(record.finalAction()).isEqualTo(InterventionKind.HUMAN_ESCALATION);
        }

        @Test
        void everyClearanceFailureModeEndsWithoutADebit() {
            List<Ctx> failures = List.of(
                    undiagnosed().mandateStatus(null),
                    undiagnosed().mandateStatus("EXPIRED"),
                    undiagnosed().validUntil(Instant.parse("2026-01-01T00:00:00Z")),
                    undiagnosed().maxAmountPaise(1L),
                    undiagnosed().riskFlagged(true),
                    undiagnosed().riskFlagged(null));

            for (Ctx ctx : failures) {
                assertThat(engine.decide(ctx.build()).finalAction().isDebit).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("GuardrailRecording")
    class GuardrailRecordingTest {

        @Test
        void everyGuardrailIsRecordedOnEveryDecisionIncludingTheOnesThatDidNotApply() {
            DecisionRecord record = engine.decide(Ctx.a().build());

            assertThat(record.guardrails()).extracting(DecisionRecord.GuardrailEvaluation::id)
                    .contains("G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9", "G10", "G11", "G12");
            assertThat(record.guardrails()).anyMatch(g -> g.result().equals("N/A"));
            assertThat(record.guardrails()).allMatch(g -> g.detail() != null && !g.detail().isBlank());
        }

        @Test
        void aSuppressedActionIsVisibleInTheRecord() {
            // 22:40 IST -> quiet hours. The nudge that was NOT sent is the evidence.
            Instant quiet = Instant.parse("2026-09-03T17:10:00Z");
            DecisionRecord record = engine.decide(Ctx.a()
                    .cause(FailureCause.CARD_EXPIRED).ladderPosition(2).now(quiet).build());

            assertThat(record.finalAction()).isEqualTo(InterventionKind.CUSTOMER_NUDGE);
            assertThat(resultOf(record, "G7")).isEqualTo("DEFER");
            assertThat(record.scheduledFor()).isAfter(quiet);
        }
    }

    @Nested
    @DisplayName("Reproducibility")
    class ReproducibilityTest {

        @Test
        void theSameContextProducesAnIdenticalRecord() {
            DecisionContext ctx = Ctx.a()
                    .cause(FailureCause.ISSUER_DECLINE_TEMPORARY).ladderPosition(1).build();
            assertThat(engine.decide(ctx)).isEqualTo(engine.decide(ctx));
        }

        @Test
        void jitteredBackoffIsStablePerCaseButDiffersBetweenCases() {
            DecisionContext a = Ctx.a().cause(FailureCause.ISSUER_DECLINE_TEMPORARY).build();
            DecisionContext b = Ctx.a().cause(FailureCause.ISSUER_DECLINE_TEMPORARY)
                    .caseId(java.util.UUID.fromString("99999999-8888-7777-6666-555555555555")).build();

            Instant first = engine.decide(a).scheduledFor();
            Instant other = engine.decide(b).scheduledFor();

            // Stable for a given case: the jitter is derived, not drawn.
            assertThat(first).isEqualTo(engine.decide(a).scheduledFor());
            assertThat(other).isEqualTo(engine.decide(b).scheduledFor());

            // But spread across cases, which is what jitter is for.
            assertThat(first).isNotEqualTo(other);
            assertThat(Math.abs(ChronoUnit.MINUTES.between(first, other)))
                    .as("jitter should perturb, not relocate")
                    .isLessThan(120);
        }
    }

    @Nested
    @DisplayName("NudgeCopyValidator")
    class NudgeCopyValidatorTest {

        private static final String AMOUNT = NudgeCopy.formatAmount(49_900);
        private static final String LINK = "https://pay.example.test/reauth/abc";

        @Test
        void templateCopyPassesItsOwnValidator() {
            for (String locale : List.of("en-IN", "hi-IN")) {
                String nudge = NudgeCopy.template(InterventionKind.CUSTOMER_NUDGE, locale, 49_900, null);
                assertThat(NudgeCopy.validate(nudge, AMOUNT, null).valid())
                        .as("nudge template for %s", locale).isTrue();

                String reauth = NudgeCopy.template(InterventionKind.REAUTH_LINK, locale, 49_900, LINK);
                assertThat(NudgeCopy.validate(reauth, AMOUNT, LINK).valid())
                        .as("reauth template for %s", locale).isTrue();
            }
        }

        @Test
        void rejectsAnInventedAmount() {
            assertThat(NudgeCopy.validate("Your payment of Rs 999.00 failed.", AMOUNT, null).reason())
                    .contains("does not contain the injected amount");
        }

        @Test
        void rejectsAnUninjectedLink() {
            String text = "Your payment of " + AMOUNT + " failed. Pay at https://evil.test/x";
            assertThat(NudgeCopy.validate(text, AMOUNT, null).reason()).contains("not injected");
        }

        @Test
        void rejectsUrgencyLanguage() {
            for (String banned : NudgeCopy.BANNED) {
                String text = "Your payment of " + AMOUNT + " failed. Please act " + banned + ".";
                assertThat(NudgeCopy.validate(text, AMOUNT, null).valid())
                        .as("should reject '%s'", banned).isFalse();
            }
        }

        @Test
        void rejectsOverlongCopy() {
            String text = "Your payment of " + AMOUNT + " failed. " + "x".repeat(400);
            assertThat(NudgeCopy.validate(text, AMOUNT, null).reason()).contains("limit");
        }

        @Test
        void failedValidationFallsBackToTheTemplateAndRecordsWhy() {
            NudgeCopy.Copy copy = NudgeCopy.validated(
                    "Pay Rs 1.00 immediately", InterventionKind.CUSTOMER_NUDGE, "en-IN", 49_900, null);

            assertThat(copy.fromTemplate()).isTrue();
            assertThat(copy.wasRejected()).isTrue();
            assertThat(copy.text()).contains(AMOUNT);
        }
    }
}

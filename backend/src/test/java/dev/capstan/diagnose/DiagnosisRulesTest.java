package dev.capstan.diagnose;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiagnosisRulesTest {

    @Nested
    @DisplayName("RiskBlockedNeverRetryable")
    class RiskBlockedNeverRetryableTest {

        @Test
        void riskBlockedIsNotRetryable() {
            // Not an optimisation. Retrying a risk-blocked debit is the behaviour
            // the safety bar calls offense-capable.
            assertThat(FailureCause.RISK_BLOCKED.retryable).isFalse();
        }

        @Test
        void everyCauseThatCannotSucceedIsMarkedNonRetryable() {
            assertThat(FailureCause.MANDATE_REVOKED.retryable).isFalse();
            assertThat(FailureCause.ACCOUNT_CLOSED.retryable).isFalse();
            assertThat(FailureCause.CARD_EXPIRED.retryable).isFalse();
            assertThat(FailureCause.MANDATE_EXPIRED.retryable).isFalse();
            assertThat(FailureCause.MANDATE_LIMIT_EXCEEDED.retryable).isFalse();
        }
    }

    @Nested
    @DisplayName("LlmSchemaViolationBecomesAbstain")
    class LlmSchemaViolationBecomesAbstainTest {

        @Test
        void labelOutsideTaxonomyAbstainsInsteadOfBeingCoerced() {
            Diagnosis result = LlmResponseParser.parse(
                    "{\"cause\":\"CUSTOMER_TOO_POOR\",\"confidence\":0.9,\"evidence\":\"made up\"}");

            assertThat(result.method()).isEqualTo(Diagnosis.DiagnosisMethod.ABSTAIN);
            // UNDIAGNOSED, not TECHNICAL_DECLINE_UNKNOWN: the latter is retryable,
            // and a rejected label is an absence of finding, not a finding.
            assertThat(result.cause()).isEqualTo(FailureCause.UNDIAGNOSED);
            assertThat(result.cause().retryable).isFalse();
            assertThat(result.confidence()).isZero();
            assertThat(result.evidence()).contains("outside taxonomy");
        }

        @Test
        void malformedJsonAbstains() {
            assertThat(LlmResponseParser.parse("not json at all").method())
                    .isEqualTo(Diagnosis.DiagnosisMethod.ABSTAIN);
        }

        @Test
        void truncatedResponseAbstains() {
            // What a max_tokens cutoff mid-object looks like.
            assertThat(LlmResponseParser.parse("{\"cause\":\"INSUFFICIENT_FU").method())
                    .isEqualTo(Diagnosis.DiagnosisMethod.ABSTAIN);
        }

        @Test
        void explicitAbstainIsHonoured() {
            Diagnosis result = LlmResponseParser.parse(
                    "{\"cause\":\"ABSTAIN\",\"confidence\":0.0,\"evidence\":\"no usable signal\"}");
            assertThat(result.method()).isEqualTo(Diagnosis.DiagnosisMethod.ABSTAIN);
            assertThat(result.evidence()).isEqualTo("no usable signal");
        }

        @Test
        void capitalisationDifferenceIsNotASchemaViolation() {
            // Structured outputs constrain the value set but not its capitalisation.
            // Treating this as a violation would show up as sporadic abstentions
            // that look like model quality.
            Diagnosis result = LlmResponseParser.parse(
                    "{\"cause\":\"insufficient_funds\",\"confidence\":0.88,\"evidence\":\"INSUFF BAL\"}");
            assertThat(result.cause()).isEqualTo(FailureCause.INSUFFICIENT_FUNDS);
            assertThat(result.method()).isEqualTo(Diagnosis.DiagnosisMethod.LLM);
        }
    }

    @Nested
    @DisplayName("MarkdownFenceStripping")
    class MarkdownFenceStrippingTest {

        @Test
        void stripsFencedJson() {
            String fenced = "```json\n{\"cause\":\"CARD_EXPIRED\",\"confidence\":0.99}\n```";
            assertThat(LlmResponseParser.parse(fenced).cause()).isEqualTo(FailureCause.CARD_EXPIRED);
        }

        @Test
        void stripsBareFences() {
            String fenced = "```\n{\"cause\":\"ACCOUNT_CLOSED\",\"confidence\":0.9}\n```";
            assertThat(LlmResponseParser.parse(fenced).cause()).isEqualTo(FailureCause.ACCOUNT_CLOSED);
        }

        @Test
        void leavesUnfencedJsonAlone() {
            assertThat(LlmResponseParser.stripFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
        }
    }

    @Nested
    @DisplayName("MandateStateRefiner")
    class MandateStateRefinerTest {

        private final Instant failedAt = Instant.parse("2026-09-02T10:00:00Z");
        private final Diagnosis fromCodeMap = new Diagnosis(
                FailureCause.MANDATE_REVOKED, 0.99,
                Diagnosis.DiagnosisMethod.CODE_MAP, "reason=mandate_not_active");

        @Test
        void validityEndedBeforeTheDebitMeansExpired() {
            Diagnosis refined = MandateStateRefiner.refine(
                    fromCodeMap, failedAt.minus(5, ChronoUnit.DAYS), failedAt);

            assertThat(refined.cause()).isEqualTo(FailureCause.MANDATE_EXPIRED);
            assertThat(refined.evidence()).contains("refined to MANDATE_EXPIRED");
            assertThat(refined.method()).isEqualTo(Diagnosis.DiagnosisMethod.CODE_MAP);
        }

        @Test
        void validityStillRunningMeansRevoked() {
            Diagnosis refined = MandateStateRefiner.refine(
                    fromCodeMap, failedAt.plus(90, ChronoUnit.DAYS), failedAt);
            assertThat(refined.cause()).isEqualTo(FailureCause.MANDATE_REVOKED);
        }

        @Test
        void leavesUnrelatedCausesAlone() {
            Diagnosis other = new Diagnosis(FailureCause.INSUFFICIENT_FUNDS, 0.99,
                    Diagnosis.DiagnosisMethod.CODE_MAP, "x");
            assertThat(MandateStateRefiner.refine(other, failedAt.minusSeconds(1), failedAt))
                    .isEqualTo(other);
        }
    }

    @Nested
    @DisplayName("Rule files load and validate at startup")
    class RuleValidationTest {

        @Test
        void tier1RulesLoadAndAllCausesResolve() {
            ErrorCodeMap map = new ErrorCodeMap();
            assertThat(map.ruleCount()).isGreaterThan(10);
        }

        @Test
        void tier2RulesCompile() {
            NarrationRules rules = new NarrationRules();
            assertThat(rules.ruleCount()).isGreaterThan(5);
        }

        @Test
        void tier1ResolvesARealRazorpayReason() {
            DiagnosisInput input = new DiagnosisInput("BAD_REQUEST_ERROR", "insufficient_funds",
                    "desc", "customer", "payment_authorization", null, "UPI_AUTOPAY");
            assertThat(new ErrorCodeMap().lookup(input))
                    .get()
                    .extracting(Diagnosis::cause)
                    .isEqualTo(FailureCause.INSUFFICIENT_FUNDS);
        }

        @Test
        void tier1DoesNotGuessOnAnUndisclosedBankDecline() {
            // payment_failed carries no cause information; mapping it would be guessing.
            DiagnosisInput input = new DiagnosisInput("BAD_REQUEST_ERROR", "payment_failed",
                    "desc", "bank", "payment_authorization", null, "UPI_AUTOPAY");
            assertThat(new ErrorCodeMap().lookup(input)).isEmpty();
        }
    }
}

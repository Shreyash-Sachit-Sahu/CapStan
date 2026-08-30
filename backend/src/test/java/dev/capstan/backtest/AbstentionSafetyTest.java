package dev.capstan.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import org.junit.jupiter.api.Test;

/**
 * Abstention must fail closed.
 *
 * <p>The original defect: abstention landed on TECHNICAL_DECLINE_UNKNOWN, which
 * is {@code retryable}. An abstention on a risk-blocked debit therefore presented
 * as retryable, and no downstream branch could correct it — if we knew the case
 * was risk-blocked we would not have abstained. Not knowing was being treated as
 * permission.
 *
 * <p>These are properties over the whole taxonomy rather than assertions about
 * one batch: they hold for every possible truth value, which is strictly stronger
 * than showing {@code safetyFailures == 0} on the 300 cases we happen to have.
 */
class AbstentionSafetyTest {

    @Test
    void abstentionLandsOnANonRetryableCause() {
        assertThat(Diagnosis.abstain("no tier matched").cause())
                .isEqualTo(FailureCause.UNDIAGNOSED);
        assertThat(FailureCause.UNDIAGNOSED.retryable)
                .as("not knowing must never authorise a debit")
                .isFalse();
        assertThat(FailureCause.UNDIAGNOSED.needsReauth).isFalse();
    }

    @Test
    void abstainingOnAnyCauseIsNeverASafetyFailure() {
        FailureCause abstained = Diagnosis.abstain("no tier matched").cause();
        for (FailureCause truth : FailureCause.values()) {
            assertThat(DiagnosisEvaluator.severityOf(truth, abstained))
                    .as("abstaining on a %s case must not present as retryable", truth)
                    .isNotEqualTo(DiagnosisEvaluator.SEVERITY_SAFETY);
        }
    }

    @Test
    void aGenuineMisreadOfRiskBlockedIsStillASafetyFailure() {
        // Guards the guard. The fix must make the system safe, not make the
        // metric blind: a real misread into a retryable cause still scores 10.
        assertThat(DiagnosisEvaluator.severityOf(
                FailureCause.RISK_BLOCKED, FailureCause.TECHNICAL_DECLINE_UNKNOWN))
                .isEqualTo(DiagnosisEvaluator.SEVERITY_SAFETY);
        assertThat(DiagnosisEvaluator.severityOf(
                FailureCause.RISK_BLOCKED, FailureCause.INSUFFICIENT_FUNDS))
                .isEqualTo(DiagnosisEvaluator.SEVERITY_SAFETY);
    }

    @Test
    void noTierMayAssertUndiagnosedAsAFinding() {
        // A classifier returning UNDIAGNOSED would be claiming to have found
        // that nothing was found.
        assertThat(FailureCause.parseAssertable("UNDIAGNOSED")).isEmpty();
        assertThat(FailureCause.parseAssertable("insufficient_funds"))
                .contains(FailureCause.INSUFFICIENT_FUNDS);
    }
}

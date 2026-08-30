package dev.capstan.policy;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.time.Instant;
import java.util.UUID;

/**
 * Test builder for {@link DecisionContext}. Defaults describe a healthy,
 * unremarkable case: active mandate, well inside validity and cap, no risk flag,
 * no history. Each test changes only the one thing it is about.
 */
final class Ctx {

    static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");   // 13:30 IST, not quiet
    static final Instant CYCLE_END = Instant.parse("2026-10-06T00:00:00Z");
    static final UUID CASE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private UUID caseId = CASE_ID;
    private Instant now = NOW;
    private FailureCause cause = FailureCause.INSUFFICIENT_FUNDS;
    private double confidence = 0.99;
    private Diagnosis.DiagnosisMethod method = Diagnosis.DiagnosisMethod.CODE_MAP;
    private String evidence = "reason=insufficient_funds";
    private long amountPaise = 49_900;
    private Instant firstFailedAt = Instant.parse("2026-09-01T10:00:00Z");
    private Instant cycleEnd = CYCLE_END;
    private String mandateStatus = "ACTIVE";
    private Instant validFrom = Instant.parse("2025-01-01T00:00:00Z");
    private Instant validUntil = Instant.parse("2027-01-01T00:00:00Z");
    private Long maxAmountPaise = 200_000L;
    private String rail = "UPI_AUTOPAY";
    private String alternateRail = "CARD";
    private boolean optedOut = false;
    private Boolean riskFlagged = Boolean.FALSE;
    private String locale = "en-IN";
    private int ladderPosition = 0;
    private int debitAttempts = 0;
    private int commsSent = 0;
    private Instant lastDebitAt = null;
    private Instant lastCommsAt = null;
    private Integer salaryDay = null;
    private int recentAttempts = 0;
    private int recentFailures = 0;
    private Instant reauthRequestedAt;
    private Instant reauthCompletedAt;
    private String failureReason = "insufficient_funds";

    static Ctx a() {
        return new Ctx();
    }

    Ctx caseId(UUID v) { this.caseId = v; return this; }
    Ctx reauthRequestedAt(Instant v) { this.reauthRequestedAt = v; return this; }
    Ctx reauthCompletedAt(Instant v) { this.reauthCompletedAt = v; return this; }
    Ctx now(Instant v) { this.now = v; return this; }
    Ctx cause(FailureCause v) { this.cause = v; return this; }
    Ctx confidence(double v) { this.confidence = v; return this; }
    Ctx method(Diagnosis.DiagnosisMethod v) { this.method = v; return this; }
    Ctx amountPaise(long v) { this.amountPaise = v; return this; }
    Ctx cycleEnd(Instant v) { this.cycleEnd = v; return this; }
    Ctx mandateStatus(String v) { this.mandateStatus = v; return this; }
    Ctx validFrom(Instant v) { this.validFrom = v; return this; }
    Ctx validUntil(Instant v) { this.validUntil = v; return this; }
    Ctx maxAmountPaise(Long v) { this.maxAmountPaise = v; return this; }
    Ctx alternateRail(String v) { this.alternateRail = v; return this; }
    Ctx optedOut(boolean v) { this.optedOut = v; return this; }
    Ctx riskFlagged(Boolean v) { this.riskFlagged = v; return this; }
    Ctx locale(String v) { this.locale = v; return this; }
    Ctx ladderPosition(int v) { this.ladderPosition = v; return this; }
    Ctx debitAttempts(int v) { this.debitAttempts = v; return this; }
    Ctx commsSent(int v) { this.commsSent = v; return this; }
    Ctx lastDebitAt(Instant v) { this.lastDebitAt = v; return this; }
    Ctx lastCommsAt(Instant v) { this.lastCommsAt = v; return this; }
    Ctx salaryDay(Integer v) { this.salaryDay = v; return this; }
    Ctx recent(int attempts, int failures) {
        this.recentAttempts = attempts;
        this.recentFailures = failures;
        return this;
    }

    DecisionContext build() {
        return new DecisionContext(caseId, now, cause, confidence, method, evidence,
                amountPaise, firstFailedAt, cycleEnd, mandateStatus, validFrom, validUntil,
                maxAmountPaise, rail, alternateRail, optedOut, riskFlagged, locale,
                ladderPosition, debitAttempts, commsSent, lastDebitAt, lastCommsAt, salaryDay,
                recentAttempts, recentFailures, failureReason,
                reauthRequestedAt, reauthCompletedAt, java.util.Set.of());
    }
}

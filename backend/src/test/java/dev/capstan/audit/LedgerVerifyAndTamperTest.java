package dev.capstan.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.execute.Fixture;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The second defence: getting past the append-only trigger still does not get
 * you an undetectable edit.
 *
 * <p>Each corruption is done exactly the way the demo endpoint does it — disable
 * the trigger, change the row, re-enable — because a verification test that
 * cannot actually produce a tampered row is testing nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerVerifyAndTamperTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuditLedger ledger;
    @Autowired
    private LedgerVerifier verifier;
    @Autowired
    private ApplicationContext context;

    private Fixture fixture;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);

        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        caseId = fixture.recoverableCase(t0.minusSeconds(86400), 249_900);
        ledger.record(caseId, AuditLedger.ACTOR_LOADER, AuditLedger.CASE_OPENED,
                Map.of("amountPaise", 249_900L), t0);
        ledger.record(caseId, AuditLedger.ACTOR_DIAGNOSE, AuditLedger.DIAGNOSIS_RESOLVED,
                Map.of("cause", "INSUFFICIENT_FUNDS", "confidence", 0.99), t0.plusSeconds(1));
        ledger.record(caseId, AuditLedger.ACTOR_POLICY, AuditLedger.DECISION_MADE,
                Map.of("finalAction", "PAYDAY_RETRY", "humanReadable", "Held retry."),
                t0.plusSeconds(2));
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
    }

    @Test
    void anUntouchedChainVerifies() {
        LedgerVerifier.CaseReport report = verifier.verifyCase(caseId);

        assertThat(report.valid()).isTrue();
        assertThat(report.events()).isEqualTo(3);
        assertThat(report.firstBreak()).isNull();
    }

    @Test
    void anEditedPayloadIsCaughtAtItsOwnSequenceNumber() {
        long targetId = ledger.trail(caseId).get(1).id();

        withTriggerDisabled(() -> jdbc.sql("""
                update audit_event
                   set payload = jsonb_set(payload, '{cause}', '"CARD_EXPIRED"')
                 where id = :id
                """).param("id", targetId).update());

        LedgerVerifier.CaseReport report = verifier.verifyCase(caseId);

        assertThat(report.valid()).isFalse();
        assertThat(report.firstBreak().seq()).isEqualTo(2);
        assertThat(report.firstBreak().kind()).isEqualTo("hash mismatch");
        assertThat(report.firstBreak().expectedHash())
                .isNotEqualTo(report.firstBreak().actualHash());
    }

    @Test
    void aRemovedEventIsCaughtAsAGap() {
        long targetId = ledger.trail(caseId).get(1).id();

        withTriggerDisabled(() -> jdbc.sql("delete from audit_event where id = :id")
                .param("id", targetId).update());

        LedgerVerifier.CaseReport report = verifier.verifyCase(caseId);

        assertThat(report.valid()).isFalse();
        assertThat(report.firstBreak().kind()).isEqualTo("sequence gap");
        assertThat(report.firstBreak().seq()).isEqualTo(2);
    }

    /**
     * {@code verifyAll} is global, so this asserts only what is true regardless
     * of what else is in the database. Asserting {@code chainsValid ==
     * casesChecked} here would make the test a claim about every chain any other
     * run happened to leave behind — including a deliberately tampered one from a
     * demo, which is exactly what broke it the first time.
     */
    @Test
    void theWholeBatchReportSeparatesEmptyChainsFromVerifiedOnes() {
        LedgerVerifier.Report report = verifier.verifyAll();

        assertThat(report.casesInBatch())
                .as("cases with no events are reported, not counted as verified")
                .isEqualTo(report.casesChecked() + report.casesWithoutEvents());
        assertThat(report.casesChecked()).isGreaterThanOrEqualTo(1);
        assertThat(report.chainsValid()).isLessThanOrEqualTo(report.casesChecked());

        // This test's own case is the one it gets to make a claim about.
        assertThat(verifier.verifyCase(caseId).valid()).isTrue();
    }

    @Test
    void theTamperEndpointIsAbsentOutsideTheDemoProfile() {
        assertThat(context.getBeanNamesForType(TamperController.class))
                .as("an unguarded endpoint that edits the audit trail would undo the trail")
                .isEmpty();
    }

    private void withTriggerDisabled(Runnable mutation) {
        jdbc.sql("alter table audit_event disable trigger trg_audit_no_update").update();
        try {
            mutation.run();
        } finally {
            jdbc.sql("alter table audit_event enable trigger trg_audit_no_update").update();
        }
    }
}

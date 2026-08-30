package dev.capstan.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.capstan.execute.Fixture;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * "The log cannot be edited" has to be enforced, not asserted in a README.
 *
 * <p>This is the first of the two defences. It stops the application entirely:
 * no amount of ordinary database access can change a row. Getting past it needs
 * DDL privilege on the table, and even then {@link LedgerVerifier} still sees
 * the edit.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditAppendOnlyTriggerTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuditLedger ledger;

    private Fixture fixture;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        ledger.record(caseId, AuditLedger.ACTOR_POLICY, AuditLedger.DECISION_MADE,
                Map.of("finalAction", "PAYDAY_RETRY"), t0);
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
    }

    @Test
    void anUpdateIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                update audit_event set payload = '{"finalAction":"nothing to see"}'::jsonb
                 where case_id = :id
                """).param("id", caseId).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void aDeleteIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("delete from audit_event where case_id = :id")
                .param("id", caseId).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void appendingIsStillAllowed() {
        ledger.record(caseId, AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_INITIATED,
                Map.of("rail", "UPI_AUTOPAY"), Instant.parse("2026-09-01T11:00:00Z"));

        assertThat(ledger.trail(caseId)).hasSize(2);
    }

    @Test
    void theRowSurvivesTheRejectedEdit() {
        try {
            jdbc.sql("update audit_event set actor = 'HUMAN:mallory' where case_id = :id")
                    .param("id", caseId).update();
        } catch (RuntimeException expected) {
            // the point is what is left behind, not the exception
        }
        assertThat(ledger.trail(caseId).get(0).actor()).isEqualTo(AuditLedger.ACTOR_POLICY);
    }
}

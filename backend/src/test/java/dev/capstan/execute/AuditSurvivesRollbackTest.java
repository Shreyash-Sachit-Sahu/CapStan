package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import dev.capstan.audit.AuditLedger;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code REQUIRES_NEW} proof.
 *
 * <p>A debit that fails rolls back the transaction that attempted it. If the
 * record of the attempt lived in that transaction it would roll back too,
 * leaving no evidence that Capstan ever touched the customer's account. The
 * audit trail has to outlive the failure it is auditing.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditSurvivesRollbackTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuditLedger ledger;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
    }

    @Test
    void theEventOutlivesTheTransactionItDescribes() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("update recovery_case set terminal_reason = 'business write' where id = :id")
                    .param("id", caseId).update();
            ledger.record(caseId, AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_INITIATED,
                    Map.of("attemptId", "test"), t0);
            throw new IllegalStateException("gateway blew up after the audit write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(terminalReason(caseId))
                .as("the business write must have rolled back, or this proves nothing")
                .isEmpty();
        assertThat(eventCount(caseId))
                .as("the audit event must survive the rollback")
                .isEqualTo(1);
    }

    @Test
    void anEventWrittenInsideACommittedTransactionIsAlsoKept() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status ->
                ledger.record(caseId, AuditLedger.ACTOR_GATEWAY, AuditLedger.GATEWAY_RESPONSE,
                        Map.of("gatewayRef", "sim_1"), t0));

        assertThat(eventCount(caseId)).isEqualTo(1);
    }

    private String terminalReason(UUID caseId) {
        return jdbc.sql("select coalesce(terminal_reason, '') from recovery_case where id = :id")
                .param("id", caseId).query(String.class).single();
    }

    private int eventCount(UUID caseId) {
        return jdbc.sql("select count(*) from audit_event where case_id = :id")
                .param("id", caseId).query(Integer.class).single();
    }
}

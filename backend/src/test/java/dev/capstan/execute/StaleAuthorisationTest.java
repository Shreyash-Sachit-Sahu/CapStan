package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * A decision authorises an action; execution happens later. Between the two the
 * case can terminate, and Phase 04 writes intervention rows ahead of execution,
 * so that gap is ordinary rather than exceptional.
 *
 * <p>Two defences, tested here: the dispatcher will not queue work for a case
 * that is no longer live, and the worker re-reads the case after claiming, for
 * the case that terminated after dispatch.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaleAuthorisationTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DebitWorker worker;
    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private SimulatedGateway gateway;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
        gateway.reset();
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
        gateway.reset();
    }

    @Test
    void aCaseThatTerminatedAfterDispatchIsNotDebited() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        UUID intervention = fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0);

        // The message is already in the worker's hands when the case terminates.
        jdbc.sql("update recovery_case set status = 'ABANDONED' where id = :id")
                .param("id", caseId).update();

        assertThat(worker.execute(intervention, t0)).isTrue();

        assertThat(gateway.movementsFor(caseId))
                .as("a terminated case must never be debited")
                .isEmpty();
        assertThat(fixture.interventionOutcome(intervention)).isEqualTo("CANCELLED");
        assertThat(cancelReason(intervention)).isEqualTo("case_abandoned_before_execution");
        assertThat(attemptCount(caseId)).isZero();
    }

    @Test
    void theDispatcherDoesNotQueueWorkForATerminatedCase() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0.minusSeconds(60));

        jdbc.sql("update recovery_case set status = 'ESCALATED' where id = :id")
                .param("id", caseId).update();

        dispatcher.dispatchDue(t0);

        assertThat(outboxRowsFor(caseId)).isZero();
    }

    @Test
    void aClaimedInterventionCannotBeCancelledOutFromUnderTheWorker() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        UUID intervention = fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0);

        // Claiming and marking are one statement, so there is no window in which
        // a cancellation could report success while the debit was in flight.
        assertThat(jdbc.sql("""
                select count(*) from intervention
                 where id = :id and executed_at is null
                """).param("id", intervention).query(Integer.class).single()).isEqualTo(1);

        var claimed = jdbc.sql("""
                update intervention set executed_at = now(), expires_at = now() + interval '10 minutes'
                 where id = :id and executed_at is null and outcome is null
                """).param("id", intervention).update();
        assertThat(claimed).isEqualTo(1);

        int cancelled = jdbc.sql("""
                update intervention set outcome = 'CANCELLED'
                 where id = :id and executed_at is null
                """).param("id", intervention).update();

        assertThat(cancelled)
                .as("a claimed row is no longer cancellable")
                .isZero();
    }

    private String cancelReason(UUID interventionId) {
        return jdbc.sql("select cancel_reason from intervention where id = :id")
                .param("id", interventionId).query(String.class).single();
    }

    private int attemptCount(UUID caseId) {
        return jdbc.sql("select count(*) from payment_attempt where case_id = :id")
                .param("id", caseId).query(Integer.class).single();
    }

    private int outboxRowsFor(UUID caseId) {
        return jdbc.sql("""
                select count(*) from outbox o
                 where o.aggregate_id in (select id from intervention where case_id = :id)
                """).param("id", caseId).query(Integer.class).single();
    }
}

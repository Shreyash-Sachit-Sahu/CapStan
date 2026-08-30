package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The headline test.
 *
 * <p>The gateway debits the customer and the response is lost. Capstan must not
 * turn that into a second charge, and the only things preventing it are the
 * reconciliation gate in {@link DebitWorker} and {@code uq_idem}. The simulated
 * gateway deliberately does not honour idempotency keys, so if either of those
 * stops working, money moves twice and this test says so.
 */
@SpringBootTest
@ActiveProfiles("test")
class NoDoubleChargeUnderTimeoutTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DebitWorker worker;
    @Autowired
    private Reconciler reconciler;
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
    void aLostResponseNeverBecomesASecondCharge() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(2 * 86400), 250_000);
        UUID first = fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0);

        gateway.inject(SimulatedGateway.Faults.parse("timeout_after_debit:1"));

        // 1. The debit lands. The response does not.
        assertThat(worker.execute(first, t0))
                .as("an unknown outcome must dead-letter, not acknowledge")
                .isFalse();
        assertThat(gateway.movementsFor(caseId)).hasSize(1);
        assertThat(attemptStates(caseId)).containsExactly("UNKNOWN");

        // 2. The retry Phase 04 authorised before any of this happened arrives.
        UUID second = fixture.intervention(caseId, 2, InterventionKind.SCHEDULED_RETRY, t0);
        assertThat(worker.execute(second, t0.plusSeconds(60))).isTrue();

        assertThat(gateway.movementsFor(caseId))
                .as("a debit ran while its predecessor's outcome was still unknown")
                .hasSize(1);
        assertThat(attemptStates(caseId))
                .as("no second payment_attempt may exist")
                .containsExactly("UNKNOWN");

        // 3. Reconciliation asks the gateway what actually happened.
        assertThat(reconciler.runDue(t0.plusSeconds(120))).isEqualTo(1);

        assertThat(gateway.movementsFor(caseId)).hasSize(1);
        assertThat(attemptStates(caseId)).containsExactly("SUCCEEDED");
        assertThat(fixture.caseStatus(caseId)).isEqualTo("RECOVERED");
        assertThat(fixture.interventionOutcome(second))
                .as("the queued retry is superseded once the truth is known")
                .isEqualTo("CANCELLED");
    }

    @Test
    void anUnresolvableUnknownEscalatesRatherThanFailing() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(2 * 86400), 250_000);
        UUID only = fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0);

        // The lookup API is down too, so the unknown genuinely cannot be resolved.
        gateway.inject(SimulatedGateway.Faults.parse("timeout_after_debit:1,reconcile_blind:true"));
        worker.execute(only, t0);

        // Spend the whole budget. FAILED at the end of this would authorise the
        // next debit against an attempt that may already have taken the money.
        for (int visit = 1; visit <= 6; visit++) {
            reconciler.runDue(t0.plusSeconds(visit * 600L));
        }

        assertThat(attemptStates(caseId)).containsExactly("UNKNOWN");
        assertThat(fixture.caseStatus(caseId)).isEqualTo("ESCALATED");
        assertThat(terminalReason(caseId)).contains("unresolved");
    }

    private List<String> attemptStates(UUID caseId) {
        return jdbc.sql("select state from payment_attempt where case_id = :id order by debit_seq")
                .param("id", caseId).query(String.class).list();
    }

    private String terminalReason(UUID caseId) {
        return jdbc.sql("select terminal_reason from recovery_case where id = :id")
                .param("id", caseId).query(String.class).single();
    }
}

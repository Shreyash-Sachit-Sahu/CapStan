package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.gateway.IdempotencyKey;
import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Two workers, one debit.
 *
 * <p>The constraint that decides it is {@code uq_case_debit_seq} on
 * {@code payment_attempt} -- and {@code uq_idem}, which catches the same race by
 * a different route, since two workers computing the same debit sequence derive
 * the same idempotency key. It is <i>not</i> {@code uq_case_attempt}: that one
 * is on {@code intervention} and counts ladder positions, which is a different
 * cap on a different thing.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttemptCapRaceTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ExecutionStore store;
    @Autowired
    private DebitWorker worker;
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
    void onlyOneOfTwoConcurrentAttemptsAtTheSameSequenceSurvives() throws Exception {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        UUID left = fixture.intervention(caseId, 1, InterventionKind.SCHEDULED_RETRY, t0);
        UUID right = fixture.intervention(caseId, 2, InterventionKind.SCHEDULED_RETRY, t0);

        String key = IdempotencyKey.idempotencyKey(caseId, 1, "UPI_AUTOPAY", 250_000);
        CyclicBarrier gun = new CyclicBarrier(2);

        Callable<Boolean> insert = () -> {
            gun.await();
            try {
                store.insertAttempt(caseId, left, 1, key, "UPI_AUTOPAY", 250_000, t0, t0);
                return true;
            } catch (Exception rejected) {
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(insert, insert));
            long winners = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return false;
                }
            }).count();

            assertThat(winners).as("exactly one attempt may be recorded").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(attemptCount(caseId)).isEqualTo(1);

        // The conflict is information, not an error: the rejected worker can find
        // the attempt that beat it and read its state, which is what stops it
        // nacking and retrying forever against a debit that already exists.
        assertThat(store.attemptByKey(key)).isPresent();
        assertThat(store.attemptByKey(key).orElseThrow().state()).isEqualTo("INITIATED");

        // And a second worker on the same case does not debit either, because the
        // attempt that won is still unreconciled.
        assertThat(worker.execute(right, t0)).isTrue();
        assertThat(gateway.movementsFor(caseId)).isEmpty();
        assertThat(attemptCount(caseId)).isEqualTo(1);
    }

    private int attemptCount(UUID caseId) {
        return jdbc.sql("select count(*) from payment_attempt where case_id = :id")
                .param("id", caseId).query(Integer.class).single();
    }
}

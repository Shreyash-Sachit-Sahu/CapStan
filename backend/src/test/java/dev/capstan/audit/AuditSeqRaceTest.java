package dev.capstan.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.execute.Fixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Two writers on one case both compute {@code seq = n + 1}. {@code uq_case_seq}
 * rejects the loser, which re-reads the tail in a <b>new</b> transaction and
 * takes the next number.
 *
 * <p>The new transaction is the part that matters. A retry inside the failed
 * transaction would throw again on every attempt, because that transaction is
 * rollback-only the moment the constraint fires — which is why the loop lives in
 * {@link AuditLedger} and the insert in {@link AuditWriter}.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditSeqRaceTest {

    private static final int WRITERS = 2;
    private static final int EVENTS_EACH = 6;

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuditLedger ledger;
    @Autowired
    private LedgerVerifier verifier;

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
    void concurrentWritersLeaveNoGapAndNoDuplicate() throws Exception {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);

        CyclicBarrier gun = new CyclicBarrier(WRITERS);
        Callable<Void> writer = () -> {
            gun.await();
            for (int i = 0; i < EVENTS_EACH; i++) {
                ledger.record(caseId, AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_INITIATED,
                        Map.of("i", i), t0.plusSeconds(i));
            }
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            for (var future : pool.invokeAll(
                    IntStream.range(0, WRITERS).mapToObj(i -> writer).toList())) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<AuditLedger.Event> trail = ledger.trail(caseId);
        assertThat(trail).hasSize(WRITERS * EVENTS_EACH);

        List<Integer> sequences = trail.stream().map(AuditLedger.Event::seq).toList();
        assertThat(sequences)
                .as("no gap, no duplicate")
                .isEqualTo(IntStream.rangeClosed(1, WRITERS * EVENTS_EACH).boxed().toList());

        assertThat(verifier.verifyCase(caseId).valid())
                .as("and the chain still links end to end")
                .isTrue();
    }
}

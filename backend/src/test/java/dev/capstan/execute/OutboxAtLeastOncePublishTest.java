package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The outbox sends and then marks, so a crash between the two republishes the
 * message. That is the correct trade -- losing a debit instruction is worse than
 * delivering one twice -- but it is only safe because the consumer is
 * idempotent, and this test asserts both halves of that sentence.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxAtLeastOncePublishTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Outbox outbox;
    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private DebitWorker worker;
    @Autowired
    private SimulatedGateway gateway;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        noOtherConsumer();
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
        jdbc.sql("delete from outbox where published_at is null").update();
        gateway.reset();
        purge();
    }

    /**
     * These tests read messages off the real broker, so a Capstan instance left
     * running on the side consumes them first and every assertion here fails as
     * an unexplained null. Maven's {@code spring-boot:run} forks, so killing the
     * wrapper leaves the app alive — this has cost real time three times over.
     * Fail with the reason instead of the symptom.
     */
    private void noOtherConsumer() {
        var info = amqpAdmin.getQueueInfo(RabbitConfig.DEBIT_QUEUE);
        if (info != null && info.getConsumerCount() > 0) {
            throw new IllegalStateException(
                    "another consumer is attached to " + RabbitConfig.DEBIT_QUEUE
                            + " (probably a running Capstan instance on port 8080). "
                            + "It will eat this test's messages. Stop it and re-run.");
        }
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
        gateway.reset();
        purge();
    }

    @Test
    void aPublishedRowIsNotPublishedAgain() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        UUID intervention = fixture.intervention(
                caseId, 1, InterventionKind.SCHEDULED_RETRY, t0.minusSeconds(60));

        assertThat(dispatcher.dispatchDue(t0)).isEqualTo(1);
        assertThat(outbox.publishBatch()).isEqualTo(1);

        Object delivered = rabbit.receiveAndConvert(RabbitConfig.DEBIT_QUEUE, 5000);
        assertThat(delivered).isNotNull();
        assertThat(delivered.toString()).contains(intervention.toString());

        assertThat(outbox.publishBatch())
                .as("a row marked published must not be sent again")
                .isZero();
    }

    @Test
    void aRedeliveredMessageDoesNotDebitTwice() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);
        UUID intervention = fixture.intervention(
                caseId, 1, InterventionKind.SCHEDULED_RETRY, t0.minusSeconds(60));

        dispatcher.dispatchDue(t0);
        outbox.publishBatch();

        // The crash the design accepts: the send landed, the mark did not.
        jdbc.sql("update outbox set published_at = null where aggregate_id = :id")
                .param("id", intervention).update();
        assertThat(outbox.publishBatch()).isEqualTo(1);

        assertThat(rabbit.receiveAndConvert(RabbitConfig.DEBIT_QUEUE, 5000)).isNotNull();
        assertThat(rabbit.receiveAndConvert(RabbitConfig.DEBIT_QUEUE, 5000))
                .as("delivery really is at-least-once")
                .isNotNull();

        // Both copies get consumed. Only one debit may result.
        assertThat(worker.execute(intervention, t0)).isTrue();
        assertThat(worker.execute(intervention, t0)).isTrue();

        assertThat(gateway.movementsFor(caseId))
                .as("the second delivery must not move money again")
                .hasSize(1);
        assertThat(attemptCount(caseId)).isEqualTo(1);
    }

    private void purge() {
        for (String queue : new String[]{RabbitConfig.DEBIT_QUEUE, RabbitConfig.COMMS_QUEUE,
                RabbitConfig.DEAD_QUEUE}) {
            try {
                amqpAdmin.purgeQueue(queue);
            } catch (Exception notYetDeclared) {
                // First run against a fresh broker; the queue appears on connect.
            }
        }
    }

    private int attemptCount(UUID caseId) {
        return jdbc.sql("select count(*) from payment_attempt where case_id = :id")
                .param("id", caseId).query(Integer.class).single();
    }
}

package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
 * A DLQ replay re-enters the policy engine; it does not re-execute the old
 * action.
 *
 * <p>By the time anyone drains the dead-letter queue the case has moved. Here
 * the billing cycle has closed since the decision was made, so the guardrails
 * now say stop -- and a blind replay would have debited anyway.
 */
@SpringBootTest
@ActiveProfiles("test")
class DlqReplayReentersPolicyTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ExecutionController controller;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private SimulatedGateway gateway;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
        gateway.reset();
        purge();
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
        gateway.reset();
        purge();
    }

    @Test
    void aReplayedInterventionIsRedecidedRatherThanReExecuted() {
        // Relative to real time, because the policy engine reads the clock.
        Instant now = Instant.now();
        UUID caseId = fixture.newCase(
                now.minus(10, ChronoUnit.DAYS), 250_000,
                now.minus(1, ChronoUnit.DAYS),      // the cycle closed yesterday
                true, "ANY", 1.0);
        UUID dead = fixture.intervention(
                caseId, 1, InterventionKind.SCHEDULED_RETRY, now.minus(2, ChronoUnit.HOURS));

        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.DEAD_KEY,
                "{\"interventionId\":\"" + dead + "\",\"caseId\":\"" + caseId + "\"}");

        Map<String, Object> result = controller.replay();

        assertThat(result.get("replayed")).isEqualTo(1);
        assertThat(fixture.interventionOutcome(dead)).isEqualTo("CANCELLED");
        assertThat(cancelReason(dead)).isEqualTo("dlq_replayed_through_policy");

        assertThat(gateway.movementsFor(caseId))
                .as("a replay must not debit")
                .isEmpty();
        assertThat(fixture.caseStatus(caseId))
                .as("the guardrails have since changed their mind")
                .isEqualTo("EXPIRED");
        assertThat(terminalReason(caseId)).isNotBlank();
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

    private String cancelReason(UUID interventionId) {
        return jdbc.sql("select cancel_reason from intervention where id = :id")
                .param("id", interventionId).query(String.class).single();
    }

    private String terminalReason(UUID caseId) {
        return jdbc.sql("select terminal_reason from recovery_case where id = :id")
                .param("id", caseId).query(String.class).single();
    }
}

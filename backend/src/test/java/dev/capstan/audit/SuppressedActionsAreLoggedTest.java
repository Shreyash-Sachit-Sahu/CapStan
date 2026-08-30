package dev.capstan.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.execute.Fixture;
import dev.capstan.policy.DecisionService;
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
 * The trail has to record the actions Capstan chose <i>not</i> to take.
 *
 * <p>A log of completed actions cannot distinguish a bounded system from an
 * unbounded one that happened not to hit a limit. These entries are the
 * difference, and they have to be individual events — the guardrail evaluations
 * are also inside {@code decision_json}, but buried in a blob nothing can list
 * them, count them, or render them one at a time.
 */
@SpringBootTest
@ActiveProfiles("test")
class SuppressedActionsAreLoggedTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DecisionService decisions;
    @Autowired
    private AuditLedger ledger;

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
    void aSuppressedNudgeIsItsOwnEvent() {
        Instant now = Instant.now();
        UUID caseId = fixture.newCase(now.minusSeconds(2 * 86400), 250_000,
                now.plusSeconds(20 * 86400), true, "ANY", 1.0);
        fixture.optOut(caseId);
        // One intervention already on file puts the ladder at CUSTOMER_NUDGE.
        fixture.intervention(caseId, 1, InterventionKind.PAYDAY_RETRY, now.minusSeconds(3600));

        decisions.decide(caseId);

        List<AuditLedger.Event> suppressed = eventsOfType(caseId, AuditLedger.COMMS_SUPPRESSED);
        assertThat(suppressed)
                .as("an opted-out customer's nudge must leave a trace")
                .isNotEmpty();
        assertThat(suppressed.get(0).payload()).contains("G1");
        assertThat(suppressed.get(0).actor()).isEqualTo(AuditLedger.ACTOR_POLICY);
    }

    @Test
    void aBlockedDebitIsItsOwnEvent() {
        Instant now = Instant.now();
        // Over the mandate's cap, so G4 blocks the debit and substitutes.
        UUID caseId = fixture.newCase(now.minusSeconds(2 * 86400), 500_000_000L,
                now.plusSeconds(20 * 86400), true, "ANY", 1.0);

        decisions.decide(caseId);

        List<AuditLedger.Event> blocked = eventsOfType(caseId, AuditLedger.GUARDRAIL_BLOCKED);
        assertThat(blocked)
                .as("a debit stopped by the amount cap must leave a trace")
                .isNotEmpty();
        assertThat(blocked.get(0).payload()).contains("G4");
    }

    @Test
    void allowedGuardrailsDoNotClutterTheTrail() {
        Instant now = Instant.now();
        UUID caseId = fixture.newCase(now.minusSeconds(2 * 86400), 250_000,
                now.plusSeconds(20 * 86400), true, "ANY", 1.0);

        decisions.decide(caseId);

        // Eleven guardrails run on every decision. Only the decisive ones become
        // events; the rest stay in decision_json where they belong.
        assertThat(eventsOfType(caseId, AuditLedger.GUARDRAIL_BLOCKED).size()
                + eventsOfType(caseId, AuditLedger.COMMS_SUPPRESSED).size())
                .isLessThan(11);
        assertThat(eventsOfType(caseId, AuditLedger.DECISION_MADE)).hasSize(1);
    }

    private List<AuditLedger.Event> eventsOfType(UUID caseId, String type) {
        return ledger.trail(caseId).stream()
                .filter(event -> event.eventType().equals(type))
                .toList();
    }
}

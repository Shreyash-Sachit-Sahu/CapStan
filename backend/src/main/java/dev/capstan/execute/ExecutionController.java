package dev.capstan.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.policy.DecisionService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual triggers for the demo and for driving execution without the scheduler. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DRAIN_LIMIT = 500;
    /** Blocking receive: a basicGet issued the instant after a publish can miss it. */
    private static final long DRAIN_TIMEOUT_MS = 500;

    private final Dispatcher dispatcher;
    private final Outbox outbox;
    private final Reconciler reconciler;
    private final DecisionService decisions;
    private final ExecutionStore store;
    private final RabbitTemplate rabbit;
    private final JdbcClient jdbc;
    private final ObjectProvider<SimulatedGateway> simulated;
    private final Clock clock;

    @PostMapping("/execute/dispatch")
    public Map<String, Object> dispatch() {
        Instant now = Instant.now(clock);
        int dispatched = dispatcher.dispatchDue(now);
        int published = outbox.publishBatch();
        return Map.of("at", now.toString(), "dispatched", dispatched, "published", published);
    }

    @PostMapping("/execute/reconcile")
    public Map<String, Object> reconcile() {
        Instant now = Instant.now(clock);
        return Map.of("at", now.toString(), "resolved", reconciler.runDue(now));
    }

    /** {@code timeout_after_debit:6,issuer_down:45m}. Simulated gateway only. */
    @PostMapping("/execute/inject")
    public Map<String, Object> inject(@RequestParam(required = false) String spec) {
        SimulatedGateway gateway = simulated.getIfAvailable();
        if (gateway == null) {
            return Map.of("error", "fault injection requires the simulated gateway");
        }
        SimulatedGateway.Faults faults = SimulatedGateway.Faults.parse(spec);
        gateway.inject(faults);
        return Map.of("injected", faults.toString());
    }

    /**
     * Drains the dead-letter queue back through the policy engine.
     *
     * <p><b>It does not re-execute the dead action.</b> A message reaches the DLQ
     * because something went wrong, and by the time anyone drains it the case has
     * moved: an attempt may have reconciled, the billing cycle may have closed, a
     * customer may have opted out. Replaying the old instruction would bypass
     * every guardrail that has since changed its mind. So the stale intervention
     * is cancelled and the case is re-decided from its current state.
     *
     * <p>Re-diagnosis is deliberately not repeated. The failure payload has not
     * changed, so Tiers 1 and 2 would return the same cause deterministically and
     * Tier 3 would spend a model call to do the same. What has changed is the
     * case's state, and that is what the policy engine reads.
     */
    @PostMapping("/admin/dlq/replay")
    public Map<String, Object> replay() {
        Instant now = Instant.now(clock);
        List<Map<String, Object>> replayed = new ArrayList<>();

        for (int i = 0; i < DRAIN_LIMIT; i++) {
            Object body = rabbit.receiveAndConvert(RabbitConfig.DEAD_QUEUE, DRAIN_TIMEOUT_MS);
            if (body == null) {
                break;
            }
            try {
                UUID interventionId = UUID.fromString(
                        JSON.readTree(body.toString()).path("interventionId").asText());
                UUID caseId = jdbc.sql("select case_id from intervention where id = :id")
                        .param("id", interventionId)
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
                if (caseId == null) {
                    continue;
                }
                store.abandonClaim(interventionId, "CANCELLED", "dlq_replayed_through_policy");

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("caseId", caseId.toString());
                entry.put("cancelledIntervention", interventionId.toString());
                entry.put("newAction", decisions.decide(caseId).finalAction().name());
                replayed.add(entry);
            } catch (Exception e) {
                // receiveAndConvert auto-acks, so this message is already off the
                // queue. Logging and continuing would not skip it -- it would
                // destroy it, in the one queue that exists so nothing is lost.
                // Put it back, then fail loudly rather than reporting a partial
                // drain as a success.
                log.error("DLQ replay failed for message {}; returning it to the queue", body, e);
                rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.DEAD_KEY, body);
                throw new IllegalStateException(
                        "DLQ replay failed after replaying " + replayed.size()
                                + " message(s); the failing message was returned to "
                                + RabbitConfig.DEAD_QUEUE, e);
            }
        }
        return Map.of("at", now.toString(), "replayed", replayed.size(), "cases", replayed);
    }
}

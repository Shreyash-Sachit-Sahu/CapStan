package dev.capstan.execute;

import dev.capstan.policy.InterventionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns due interventions into outbox rows.
 *
 * <p>The status filter is the first of two defences against a terminated case
 * meeting a queued debit. It is not sufficient on its own -- a case can
 * terminate after dispatch and before the worker runs, which is ordinary rather
 * than exceptional, since Phase 04 writes intervention rows ahead of execution.
 * {@link DebitWorker} re-reads the status after claiming for exactly that
 * reason.
 */
@Service
@RequiredArgsConstructor
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    /**
     * How long to wait before dispatching an intervention again. Bounds queue
     * churn while still re-driving work whose worker died mid-flight.
     */
    private static final Duration REDRIVE_AFTER = Duration.ofMinutes(5);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final Outbox outbox;

    private record Due(UUID interventionId, UUID caseId, InterventionKind kind) {
    }

    @Transactional
    public int dispatchDue(Instant now) {
        List<Due> due = jdbc.sql("""
                select i.id, i.case_id, i.kind
                  from intervention i
                  join recovery_case c on c.id = i.case_id
                 where i.executed_at is null
                   and i.outcome is null
                   and i.scheduled_for <= :now
                   and c.status = 'SCHEDULED'
                   and i.kind not in ('ABANDON', 'HUMAN_ESCALATION')
                   and not exists (select 1 from outbox o
                                    where o.aggregate_id = i.id
                                      and o.created_at > :redriveFloor)
                 order by i.scheduled_for
                 limit %d
                """.formatted(BATCH))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("redriveFloor", now.minus(REDRIVE_AFTER).atOffset(ZoneOffset.UTC))
                .query((ResultSet rs, int rowNum) -> map(rs))
                .list();

        for (Due row : due) {
            outbox.enqueue(row.interventionId(), queueFor(row.kind()),
                    """
                    {"interventionId":"%s","caseId":"%s","kind":"%s"}"""
                            .formatted(row.interventionId(), row.caseId(), row.kind()),
                    now);
        }
        if (!due.isEmpty()) {
            log.info("Dispatched {} interventions due at or before {}", due.size(), now);
        }
        return due.size();
    }

    private static String queueFor(InterventionKind kind) {
        return kind.isComms ? RabbitConfig.COMMS_QUEUE : RabbitConfig.DEBIT_QUEUE;
    }

    private static Due map(ResultSet rs) throws SQLException {
        return new Due(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                InterventionKind.valueOf(rs.getString("kind")));
    }
}

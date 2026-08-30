package dev.capstan.execute;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox.
 *
 * <p>A decision commits an intent to a table; publishing to RabbitMQ happens
 * afterwards. Nothing that can block on a network ever shares a transaction
 * with a business write.
 *
 * <p>Delivery is at-least-once: {@link #publishBatch} sends and then marks, so a
 * crash between the two republishes the message. That is safe only because the
 * consumer is idempotent -- see the note in {@link DebitWorker}.
 */
@Service
@RequiredArgsConstructor
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    /** Arbitrary but fixed: only one instance drains the outbox at a time. */
    private static final long PUBLISH_LOCK = 918273L;
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;

    private record Row(long id, String type, String payload) {
    }

    /** Joins the caller's transaction on purpose: intent and row commit together. */
    @Transactional
    public void enqueue(UUID aggregateId, String type, String payloadJson, Instant now) {
        jdbc.sql("""
                insert into outbox (aggregate_id, type, payload, created_at)
                values (:aggregateId, :type, cast(:payload as jsonb), :now)
                """)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", payloadJson)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * @return how many rows were published, so a caller driving this explicitly
     *         can tell whether the queue drained
     */
    @Transactional
    public int publishBatch() {
        // Session-level would leak across pooled connections; xact scope releases
        // on commit whether or not this method returns normally.
        Boolean got = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                .param("key", PUBLISH_LOCK)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(got)) {
            return 0;
        }

        List<Row> rows = jdbc.sql("""
                select id, type, payload from outbox
                 where published_at is null
                 order by id
                 limit %d
                 for update skip locked
                """.formatted(BATCH))
                .query((ResultSet rs, int rowNum) -> map(rs))
                .list();

        for (Row row : rows) {
            rabbit.convertAndSend(RabbitConfig.EXCHANGE, row.type(), row.payload());
            jdbc.sql("update outbox set published_at = now(), attempts = attempts + 1 where id = :id")
                    .param("id", row.id())
                    .update();
        }
        if (!rows.isEmpty()) {
            log.debug("Published {} outbox rows", rows.size());
        }
        return rows.size();
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("type"), rs.getString("payload"));
    }
}

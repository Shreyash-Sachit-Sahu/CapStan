package dev.capstan.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Keeps the last measurement of each kind so the cockpit can render without
 * re-running anything.
 *
 * <p>A run takes about eighty seconds. A judge has ninety. Those two numbers are
 * the entire reason this class exists.
 */
@Service
@RequiredArgsConstructor
public class ReportStore {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final JdbcClient jdbc;
    private final Clock clock;

    public void save(String kind, String batch, String variant, Object body) {
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise " + kind + " report", e);
        }
        jdbc.sql("""
                insert into backtest_report (kind, batch, variant, body, created_at)
                values (:kind, :batch, :variant, cast(:body as jsonb), :now)
                on conflict (kind, batch, variant)
                do update set body = excluded.body, created_at = excluded.created_at
                """)
                .param("kind", kind)
                .param("batch", batch)
                .param("variant", variant == null ? "" : variant)
                .param("body", json)
                .param("now", Instant.now(clock).atOffset(ZoneOffset.UTC))
                .update();
    }

    private Optional<JsonNode> load(String kind, String batch, String variant) {
        return jdbc.sql("""
                select body::text from backtest_report
                 where kind = :kind and batch = :batch and variant = :variant
                """)
                .param("kind", kind).param("batch", batch).param("variant", variant)
                .query(String.class).optional()
                .map(text -> {
                    try {
                        return JSON.readTree(text);
                    } catch (Exception e) {
                        throw new IllegalStateException("stored report is not JSON", e);
                    }
                });
    }

    /**
     * Everything the batch view needs, in one round trip.
     *
     * <p>The sweep is the headline — ten batches, median — and the single-batch
     * run is carried alongside it rather than instead of it. Both are labelled at
     * the render site: two different sets of numbers for the same three arms, with
     * no indication of which is which, is how a reader concludes you showed
     * whichever was higher.
     */
    public Map<String, Object> dashboard(String batch) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("sweep", load("sweep", "*", "").orElse(null));
        out.put("run", load("run", batch, "").orElse(null));
        out.put("ablations", load("ablations", batch, "").orElse(null));

        Map<String, Object> budgets = new LinkedHashMap<>();
        for (String n : new String[]{"1", "2", "3"}) {
            load("budget", batch, n).ifPresent(body -> budgets.put(n, body));
        }
        out.put("equalBudget", budgets);
        out.put("generatedAt", jdbc.sql("""
                select max(created_at) from backtest_report
                """).query(java.time.OffsetDateTime.class).optional()
                .map(java.time.OffsetDateTime::toInstant).map(Instant::toString).orElse(null));
        return out;
    }
}

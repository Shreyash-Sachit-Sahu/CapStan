package dev.capstan.diagnose;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three-tier cascade: deterministic map, then narration rules, then the LLM,
 * then abstention. Each tier is tried only when the one above it has no answer.
 */
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);
    private static final String CACHE_PREFIX = "diag:v1:";
    private static final java.time.Duration CACHE_TTL = java.time.Duration.ofHours(24);

    private final ErrorCodeMap codeMap;
    private final NarrationRules narrationRules;
    private final LlmClassifier llm;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final dev.capstan.audit.AuditLedger ledger;

    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong llmLatencyMillis = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong schemaViolations = new AtomicLong();

    public record CaseRow(UUID caseId, DiagnosisInput input,
                          Instant mandateValidUntil, Instant firstFailedAt) {
    }

    public record Stats(long llmCalls, double meanLlmLatencyMillis,
                        long cacheHits, long cacheMisses, double cacheHitRate,
                        long schemaViolations, boolean llmEnabled) {
    }

    /**
     * Runs the cascade without persisting. Pure with respect to the database, so
     * the eval harness can score a batch without mutating it.
     */
    public Diagnosis classify(CaseRow row) {
        DiagnosisInput input = row.input();

        Optional<Diagnosis> tier1 = codeMap.lookup(input);
        if (tier1.isPresent()) {
            return refine(tier1.get(), row);
        }

        Optional<Diagnosis> tier2 = narrationRules.lookup(input);
        if (tier2.isPresent()) {
            return refine(tier2.get(), row);
        }

        return refine(tier3(input), row);
    }

    private Diagnosis refine(Diagnosis diagnosis, CaseRow row) {
        return MandateStateRefiner.refine(diagnosis, row.mandateValidUntil(), row.firstFailedAt());
    }

    private Diagnosis tier3(DiagnosisInput input) {
        if (!llm.enabled()) {
            return llm.classify(input);
        }

        String key = CACHE_PREFIX + input.signature();
        String cached = safeCacheGet(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return FailureCause.parse(cached)
                    .map(cause -> new Diagnosis(cause, 0.75, Diagnosis.DiagnosisMethod.LLM,
                            "cached classification for an identical payload"))
                    .orElseGet(() -> Diagnosis.abstain("cached value no longer parses"));
        }
        cacheMisses.incrementAndGet();

        long startedAt = System.nanoTime();
        Diagnosis result;
        try {
            result = llm.classify(input);
        } catch (RuntimeException e) {
            log.warn("Tier 3 classification failed, abstaining: {}", e.toString());
            result = Diagnosis.abstain("classifier error: " + e.getClass().getSimpleName());
        }
        llmCalls.incrementAndGet();
        llmLatencyMillis.addAndGet((System.nanoTime() - startedAt) / 1_000_000);

        if (result.method() == Diagnosis.DiagnosisMethod.ABSTAIN) {
            schemaViolations.incrementAndGet();
        } else {
            safeCachePut(key, result.cause().name());
        }
        return result;
    }

    private String safeCacheGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, continuing uncached: {}", e.toString());
            return null;
        }
    }

    private void safeCachePut(String key, String value) {
        try {
            redis.opsForValue().set(key, value, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, result not cached: {}", e.toString());
        }
    }

    @Transactional
    public Diagnosis diagnoseAndPersist(UUID caseId) {
        CaseRow row = loadCase(caseId).orElseThrow(() ->
                new IllegalArgumentException("no such case: " + caseId));

        long startedAt = System.nanoTime();
        Diagnosis diagnosis = classify(row);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;

        persist(caseId, diagnosis);

        java.time.Instant at = java.time.Instant.now(clock);
        ledger.record(caseId, dev.capstan.audit.AuditLedger.ACTOR_DIAGNOSE,
                dev.capstan.audit.AuditLedger.DIAGNOSIS_ATTEMPTED,
                java.util.Map.of("method", diagnosis.method().name(),
                        "latencyMs", latencyMs,
                        "rawErrorReason", String.valueOf(row.input().rawErrorReason())), at);
        ledger.record(caseId, dev.capstan.audit.AuditLedger.ACTOR_DIAGNOSE,
                dev.capstan.audit.AuditLedger.DIAGNOSIS_RESOLVED,
                java.util.Map.of("cause", diagnosis.cause().name(),
                        "confidence", diagnosis.confidence(),
                        "evidence", String.valueOf(diagnosis.evidence())), at);
        return diagnosis;
    }

    private void persist(UUID caseId, Diagnosis diagnosis) {
        jdbc.sql("""
                update recovery_case
                   set diagnosed_cause = :cause,
                       diagnosis_confidence = :confidence,
                       diagnosis_method = :method,
                       diagnosis_evidence = :evidence,
                       status = 'DIAGNOSED',
                       updated_at = :now
                 where id = :id
                """)
                .param("cause", diagnosis.cause().name())
                .param("confidence", diagnosis.confidence())
                .param("method", diagnosis.method().name())
                .param("evidence", diagnosis.evidence())
                .param("now", OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC))
                .param("id", caseId)
                .update();
    }

    public Optional<CaseRow> loadCase(UUID caseId) {
        return jdbc.sql(SELECT_CASE + " where c.id = :id")
                .param("id", caseId)
                .query(DiagnosisService::mapRow)
                .optional();
    }

    public List<CaseRow> loadBatch(String batchLabel) {
        return jdbc.sql(SELECT_CASE + " where c.batch_label = :batch order by c.id")
                .param("batch", batchLabel)
                .query(DiagnosisService::mapRow)
                .list();
    }

    private static final String SELECT_CASE = """
            select c.id, c.raw_error_code, c.raw_error_reason, c.raw_error_desc,
                   c.raw_error_source, c.raw_error_step, c.bank_narration,
                   c.first_failed_at, m.rail, m.valid_until
              from recovery_case c
              join mandate m on m.id = c.mandate_id
            """;

    private static CaseRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DiagnosisInput input = new DiagnosisInput(
                rs.getString("raw_error_code"),
                rs.getString("raw_error_reason"),
                rs.getString("raw_error_desc"),
                rs.getString("raw_error_source"),
                rs.getString("raw_error_step"),
                rs.getString("bank_narration"),
                rs.getString("rail"));
        return new CaseRow(
                rs.getObject("id", UUID.class),
                input,
                toInstant(rs.getObject("valid_until", OffsetDateTime.class)),
                toInstant(rs.getObject("first_failed_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public Stats stats() {
        long calls = llmCalls.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long lookups = hits + misses;
        return new Stats(
                calls,
                calls == 0 ? 0.0 : (double) llmLatencyMillis.get() / calls,
                hits, misses,
                lookups == 0 ? 0.0 : (double) hits / lookups,
                schemaViolations.get(),
                llm.enabled());
    }

    public void resetStats() {
        llmCalls.set(0);
        llmLatencyMillis.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        schemaViolations.set(0);
    }
}

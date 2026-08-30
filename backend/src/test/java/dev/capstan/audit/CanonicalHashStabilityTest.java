package dev.capstan.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.execute.Fixture;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Canonical JSON is where hash chains break.
 *
 * <p>The important test here is the database round trip. Serialising the same
 * object twice in one process and getting the same bytes proves almost nothing:
 * it would pass while the real path — write, store as {@code jsonb}, read back,
 * re-hash — was broken, because PostgreSQL reorders object keys on storage and
 * the in-process test never sees that happen.
 */
@SpringBootTest
@ActiveProfiles("test")
class CanonicalHashStabilityTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuditLedger ledger;
    @Autowired
    private LedgerVerifier verifier;

    private Fixture fixture;

    /**
     * Deliberately hostile: insertion order is not alphabetical, key lengths
     * differ so length-first and lexicographic orderings disagree, and there is
     * a double, a long, a nested object and non-ASCII text.
     */
    private static Map<String, Object> awkwardPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zebra", "inserted first, sorts last");
        payload.put("a", 1);
        payload.put("amountPaise", 250_000L);
        payload.put("confidence", 0.95);
        payload.put("aLongerKeyThanTheRest", true);
        payload.put("nested", new LinkedHashMap<>(Map.of("z", 1, "a", 2)));
        payload.put("evidence", "mandate revoked — Rs 2,423.98 short");
        return payload;
    }

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
    void aPayloadKeepsItsHashAcrossTheDatabaseRoundTrip() {
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);

        ledger.record(caseId, AuditLedger.ACTOR_POLICY, AuditLedger.DECISION_MADE,
                awkwardPayload(), t0);

        AuditLedger.Event stored = ledger.trail(caseId).get(0);
        String recomputed = CanonicalJson.chainHash(
                stored.caseId(), stored.seq(), stored.eventType(),
                CanonicalJson.canonicalise(stored.payload()),
                stored.occurredAt(), stored.prevHash());

        assertThat(recomputed)
                .as("the hash written must survive a trip through jsonb")
                .isEqualTo(stored.hash());
        assertThat(verifier.verifyCase(caseId).valid()).isTrue();
    }

    @Test
    void postgresReallyDoesReorderTheKeys() {
        // Without this, the test above could be green because nothing reorders
        // anything, and the canonicalisation would be untested scaffolding.
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        UUID caseId = fixture.recoverableCase(t0.minusSeconds(86400), 250_000);

        String written = CanonicalJson.canonicalise(awkwardPayload());
        ledger.record(caseId, AuditLedger.ACTOR_POLICY, AuditLedger.DECISION_MADE,
                awkwardPayload(), t0);
        String readBack = ledger.trail(caseId).get(0).payload();

        assertThat(readBack)
                .as("jsonb is expected to return a different key order than we wrote")
                .isNotEqualTo(written);
        assertThat(CanonicalJson.canonicalise(readBack))
                .as("but canonicalising both sides makes them identical")
                .isEqualTo(written);
    }

    /**
     * The payload is not the only hashed input, and it was not the one that
     * broke. {@code Instant} carries nanoseconds and {@code timestamptz} stores
     * microseconds, so an instant with sub-microsecond precision is signed at
     * one value and stored at another.
     *
     * <p>Every other test here used {@code Instant.parse("...:00Z")}, whose
     * nanos are zero — so they round-tripped perfectly and passed while the live
     * path failed on 74 of 81 chains. This is the case that would have caught it.
     */
    @Test
    void anInstantWithNanosecondsStillVerifies() {
        Instant awkward = Instant.parse("2026-09-01T10:00:00Z").plusNanos(516_123_456L);
        assertThat(awkward.getNano() % 1_000)
                .as("the test instant must actually carry sub-microsecond precision")
                .isNotZero();

        UUID caseId = fixture.recoverableCase(awkward.minusSeconds(86400), 250_000);
        ledger.record(caseId, AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_INITIATED,
                awkwardPayload(), awkward);

        assertThat(verifier.verifyCase(caseId).valid())
                .as("nanosecond precision must not read as tampering")
                .isTrue();
        assertThat(ledger.trail(caseId).get(0).occurredAt().getNano() % 1_000)
                .as("stored at microsecond precision, which is what was signed")
                .isZero();
    }

    @Test
    void canonicalisingIsIdempotent() {
        String once = CanonicalJson.canonicalise(awkwardPayload());
        assertThat(CanonicalJson.canonicalise(once)).isEqualTo(once);
        assertThat(CanonicalJson.canonicalise(CanonicalJson.canonicalise(once))).isEqualTo(once);
    }

    @Test
    void numericFormattingCannotDrift() {
        // Scientific and plain notation are the same number and must not become
        // two different hashes. WRITE_BIGDECIMAL_AS_PLAIN is what makes this hold.
        assertThat(CanonicalJson.canonicalise("{\"n\":1E+2}"))
                .isEqualTo(CanonicalJson.canonicalise("{\"n\":100}"));

        // Scale is preserved rather than normalised, on both sides: 0.50 stays
        // 0.50 through Jackson and through jsonb's numeric, so the two agree.
        // (They are deliberately not asserted equal to 0.5 -- that would be
        // claiming a normalisation neither side performs.)
        assertThat(CanonicalJson.canonicalise("{\"n\":0.50}")).contains("0.50");
        assertThat(CanonicalJson.canonicalise(CanonicalJson.canonicalise("{\"n\":0.50}")))
                .isEqualTo(CanonicalJson.canonicalise("{\"n\":0.50}"));
    }

    @Test
    void aDifferentPayloadProducesADifferentHash() {
        UUID caseId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");

        String left = CanonicalJson.chainHash(caseId, 1, "X",
                CanonicalJson.canonicalise(Map.of("a", 1)), t0, CanonicalJson.GENESIS);
        String right = CanonicalJson.chainHash(caseId, 1, "X",
                CanonicalJson.canonicalise(Map.of("a", 2)), t0, CanonicalJson.GENESIS);

        assertThat(left).isNotEqualTo(right);
    }
}

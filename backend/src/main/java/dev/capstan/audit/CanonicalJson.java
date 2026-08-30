package dev.capstan.audit;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical serialisation and the chain hash.
 *
 * <h2>Why this is not the web MVC mapper</h2>
 *
 * <p>The hash is taken over the payload's JSON text, so any difference in that
 * text is indistinguishable from tampering. The MVC mapper is configured for
 * readability and can change when an unrelated endpoint needs it to.
 *
 * <h2>Why parsing and re-writing, rather than sorting keys</h2>
 *
 * <p>The payload is stored as {@code jsonb}, and PostgreSQL reorders object keys
 * when it stores that type -- by key length first, then bytewise. Jackson sorts
 * lexicographically. So the obvious fix, "sort the keys before hashing", fails
 * on every row while looking correct, because the text read back is ordered
 * differently from the text that was hashed.
 *
 * <p>{@link #canonicalise} sidesteps that instead of trying to match it. Both
 * the write path (from a Java value) and the verify path (from the database's
 * {@code jsonb} text) parse into plain {@code Map}s and re-serialise with
 * {@code ORDER_MAP_ENTRIES_BY_KEYS}. Whatever order PostgreSQL chose internally
 * is discarded by the parse, so the two paths produce identical bytes without
 * either of them needing to know how the other orders anything.
 *
 * <p>Numbers get the same treatment for the same reason: floats parse to
 * {@code BigDecimal} and are written plain, so {@code 1E+2} and {@code 100}
 * cannot become two different hashes for one value.
 */
public final class CanonicalJson {

    /** {@code prev_hash} of the first event in a chain. */
    public static final String GENESIS = "0".repeat(64);

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private CanonicalJson() {
    }

    /** A Java value to canonical JSON text. */
    public static String canonicalise(Object value) {
        try {
            // Via text on purpose: this is the identical pipeline the verify
            // path runs, so the two cannot drift apart.
            return canonicalise(CANONICAL.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise audit payload", e);
        }
    }

    /** JSON text -- including whatever {@code jsonb} handed back -- to canonical form. */
    public static String canonicalise(String json) {
        try {
            return CANONICAL.writeValueAsString(CANONICAL.readValue(json, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException("could not canonicalise audit payload: " + json, e);
        }
    }

    /** Reads a payload for display. Not on the hash path. */
    public static com.fasterxml.jackson.databind.JsonNode read(String json) {
        try {
            return CANONICAL.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("could not read audit payload: " + json, e);
        }
    }

    /**
     * The chain hash. {@code payloadJson} must already be canonical.
     */
    public static String chainHash(UUID caseId, int seq, String type, String payloadJson,
                                   Instant at, String prevHash) {
        String canonical = String.join("|",
                caseId.toString(), Integer.toString(seq), type,
                payloadJson,
                at.toString(), prevHash);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

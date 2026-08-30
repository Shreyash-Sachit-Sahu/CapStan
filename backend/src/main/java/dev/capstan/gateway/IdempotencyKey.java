package dev.capstan.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deterministic, so a retry of the <i>same</i> attempt reuses the same key
 * while a <i>new</i> authorised attempt gets a new one.
 *
 * <p>{@code attemptNo} is {@code payment_attempt.debit_seq}, not the ladder
 * position. The key identifies a debit, and a ladder rung that sends a nudge or
 * asks the gateway what happened is not one.
 *
 * <p>Backed by {@code uq_idem}. That constraint, not the gateway, is what
 * enforces exactly-once here -- see {@link RazorpayTestGateway}.
 */
public final class IdempotencyKey {

    private IdempotencyKey() {
    }

    public static String idempotencyKey(UUID caseId, int attemptNo, String rail, long amountPaise) {
        String raw = "capstan:v1:%s:%d:%s:%d".formatted(caseId, attemptNo, rail, amountPaise);
        try {
            return "cap_" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8))
            ).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package dev.capstan.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one link to one case's chain, in its own transaction.
 *
 * <p><b>This is deliberately a separate bean from {@link AuditLedger}.</b> Two
 * things depend on it and both are easy to lose.
 *
 * <p>First, {@code REQUIRES_NEW} only takes effect through the Spring proxy. If
 * the retry loop called this method on itself it would be a plain Java call: no
 * new transaction, so the audit write would join the business transaction and
 * roll back with it -- silently undoing the one property Phase 05's
 * {@code AuditSurvivesRollbackTest} exists to guarantee.
 *
 * <p>Second, the retry cannot live inside this method. When the
 * {@code uq_case_seq} insert loses a race, its transaction is marked
 * rollback-only, and every further statement in it fails regardless of how many
 * attempts the loop is willing to make. The retry has to start a <i>new</i>
 * transaction, which means calling this method again from outside.
 */
@Service
@RequiredArgsConstructor
public class AuditWriter {

    private final JdbcClient jdbc;

    private record Tail(int seq, String hash) {
    }

    /**
     * @return the sequence number written
     * @throws org.springframework.dao.DataIntegrityViolationException if another
     *         writer took this sequence number first; the caller retries
     */
    /**
     * PostgreSQL's {@code timestamptz} holds microseconds; {@code Instant} holds
     * nanoseconds. Hashing an un-truncated instant hashes a value the database
     * cannot store, so the row that comes back differs from the row that was
     * signed and every verification reports tampering. Truncating here — before
     * both the hash and the insert — means the value signed is the value stored.
     */
    private static final java.time.temporal.ChronoUnit STORED_PRECISION =
            java.time.temporal.ChronoUnit.MICROS;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int append(UUID caseId, String actor, String eventType,
                      String canonicalPayload, Instant rawAt) {
        Instant at = rawAt.truncatedTo(STORED_PRECISION);
        // Serialise writers on this case before reading the tail.
        //
        // Retry alone is not enough, and the seq-race test proved it: when a
        // writer inserts a duplicate key whose conflicting row is still
        // uncommitted, PostgreSQL makes it *wait* for the other transaction and
        // then fails it. Under sustained contention the loser stays permanently
        // one step behind the winner and burns every retry it has. The lock
        // turns that storm into a short wait; the retry below it stays as the
        // backstop for anything the lock does not cover.
        //
        // xact-scoped, so it releases on commit or rollback without a finally.
        // The namespace keeps it clear of the outbox publisher's lock.
        jdbc.sql("select pg_advisory_xact_lock(918274, hashtext(cast(:caseId as text)))")
                .param("caseId", caseId.toString())
                .query(Object.class)
                .single();

        Optional<Tail> tail = jdbc.sql("""
                select seq, hash from audit_event
                 where case_id = :caseId
                 order by seq desc
                 limit 1
                """)
                .param("caseId", caseId)
                .query((rs, rowNum) -> new Tail(rs.getInt("seq"), rs.getString("hash")))
                .optional();

        int seq = tail.map(t -> t.seq() + 1).orElse(1);
        String prevHash = tail.map(Tail::hash).orElse(CanonicalJson.GENESIS);
        String hash = CanonicalJson.chainHash(caseId, seq, eventType, canonicalPayload, at, prevHash);

        jdbc.sql("""
                insert into audit_event (case_id, seq, event_type, actor, payload,
                                         occurred_at, prev_hash, hash)
                values (:caseId, :seq, :eventType, :actor, cast(:payload as jsonb),
                        :occurredAt, :prevHash, :hash)
                """)
                .param("caseId", caseId)
                .param("seq", seq)
                .param("eventType", eventType)
                .param("actor", actor)
                .param("payload", canonicalPayload)
                .param("occurredAt", at.atOffset(ZoneOffset.UTC))
                .param("prevHash", prevHash)
                .param("hash", hash)
                .update();

        return seq;
    }
}

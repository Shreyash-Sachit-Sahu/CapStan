package dev.capstan.audit;

import dev.capstan.audit.AuditLedger.Event;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Recomputes each case's chain and reports the first place it stops matching.
 *
 * <p>Three things can be wrong and they are reported separately, because they
 * mean different things: a <b>gap</b> says an event was removed, a <b>broken
 * link</b> says one was inserted or reordered, and a <b>hash mismatch</b> says
 * one was edited in place.
 */
@Service
@RequiredArgsConstructor
public class LedgerVerifier {

    private final AuditLedger ledger;

    public record Break(UUID caseId, int seq, String kind, String expectedHash, String actualHash) {
    }

    public record CaseReport(UUID caseId, int events, boolean valid, Break firstBreak) {
    }

    /**
     * @param casesInBatch      every case on file
     * @param casesChecked      cases that actually have a chain
     * @param casesWithoutEvents cases with nothing to verify -- reported rather
     *                          than counted as verified, since a case that was
     *                          never diagnosed has no trail to be right about
     */
    public record Report(int casesInBatch, int casesChecked, int casesWithoutEvents,
                         int chainsValid, Break firstBreak) {
    }

    public CaseReport verifyCase(UUID caseId) {
        List<Event> events = ledger.trail(caseId);

        String expectedPrev = CanonicalJson.GENESIS;
        int expectedSeq = 1;

        for (Event event : events) {
            if (event.seq() != expectedSeq) {
                return broken(caseId, events.size(), expectedSeq, "sequence gap",
                        String.valueOf(expectedSeq), String.valueOf(event.seq()));
            }
            if (!expectedPrev.equals(event.prevHash())) {
                return broken(caseId, events.size(), event.seq(), "broken link",
                        expectedPrev, event.prevHash());
            }
            // Re-canonicalise from what the database actually returned. jsonb
            // reorders keys on storage, so hashing the raw text would fail on
            // every row; parsing and re-writing discards that ordering.
            String recomputed = CanonicalJson.chainHash(
                    event.caseId(), event.seq(), event.eventType(),
                    CanonicalJson.canonicalise(event.payload()),
                    event.occurredAt(), event.prevHash());

            if (!recomputed.equals(event.hash())) {
                return broken(caseId, events.size(), event.seq(), "hash mismatch",
                        recomputed, event.hash());
            }
            expectedPrev = event.hash();
            expectedSeq++;
        }
        return new CaseReport(caseId, events.size(), true, null);
    }

    public Report verifyAll() {
        List<UUID> withEvents = ledger.casesWithEvents();
        int valid = 0;
        Break firstBreak = null;

        for (UUID caseId : withEvents) {
            CaseReport report = verifyCase(caseId);
            if (report.valid()) {
                valid++;
            } else if (firstBreak == null) {
                firstBreak = report.firstBreak();
            }
        }
        int total = ledger.caseCount();
        return new Report(total, withEvents.size(), total - withEvents.size(), valid, firstBreak);
    }

    private static CaseReport broken(UUID caseId, int events, int seq, String kind,
                                     String expected, String actual) {
        return new CaseReport(caseId, events, false, new Break(caseId, seq, kind, expected, actual));
    }
}

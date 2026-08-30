package dev.capstan.audit;

import dev.capstan.audit.AuditLedger.Event;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of the ledger. Nothing here can write to it. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LedgerController {

    private final AuditLedger ledger;
    private final LedgerVerifier verifier;
    private final TrailNarrator narrator;
    private final JdbcClient jdbc;

    /** The ordered chain plus its plain-English rendering. */
    @GetMapping("/cases/{caseId}/trail")
    public Map<String, Object> trail(@PathVariable UUID caseId) {
        List<Event> events = ledger.trail(caseId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", caseId.toString());
        body.put("events", events);
        body.put("narrative", narrator.narrate(events));
        // The full DecisionRecord, not the summary the DECISION_MADE payload keeps.
        // The why-panel has to show every guardrail evaluation including the ones
        // that allowed -- a panel listing only the blocks would be advocacy.
        body.put("decisions", decisions(caseId));
        // Verification travels with the trail: a narrative nobody checked is
        // just a story.
        body.put("verification", verifier.verifyCase(caseId));
        return body;
    }

    private List<Map<String, Object>> decisions(UUID caseId) {
        return jdbc.sql("""
                select attempt_no, kind, scheduled_for, outcome, cancel_reason,
                       decision_json::text as decision
                  from intervention where case_id = :id order by attempt_no
                """)
                .param("id", caseId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("attemptNo", rs.getInt("attempt_no"));
                    row.put("kind", rs.getString("kind"));
                    row.put("scheduledFor", rs.getObject("scheduled_for",
                            java.time.OffsetDateTime.class).toInstant().toString());
                    row.put("outcome", rs.getString("outcome"));
                    row.put("cancelReason", rs.getString("cancel_reason"));
                    row.put("decision", CanonicalJson.read(rs.getString("decision")));
                    return row;
                })
                .list();
    }

    @GetMapping("/ledger/verify")
    public LedgerVerifier.Report verify() {
        return verifier.verifyAll();
    }

    @GetMapping("/ledger/verify/{caseId}")
    public LedgerVerifier.CaseReport verifyCase(@PathVariable UUID caseId) {
        return verifier.verifyCase(caseId);
    }
}

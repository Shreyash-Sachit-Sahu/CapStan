package dev.capstan.audit;

import dev.capstan.audit.AuditLedger.Event;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    /** The ordered chain plus its plain-English rendering. */
    @GetMapping("/cases/{caseId}/trail")
    public Map<String, Object> trail(@PathVariable UUID caseId) {
        List<Event> events = ledger.trail(caseId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", caseId.toString());
        body.put("events", events);
        body.put("narrative", narrator.narrate(events));
        // Verification travels with the trail: a narrative nobody checked is
        // just a story.
        body.put("verification", verifier.verifyCase(caseId));
        return body;
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

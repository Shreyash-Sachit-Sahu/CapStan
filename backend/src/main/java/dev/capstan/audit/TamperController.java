package dev.capstan.audit;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corrupts a ledger row so the demo can show verification failing.
 *
 * <p><b>Demo profile only.</b> It is not reachable in the default profile and it
 * announces itself at startup when it is active. An unguarded endpoint that
 * edits the audit trail would undo everything the trail is for.
 *
 * <p>It has to switch off {@code trg_audit_no_update} to do its work, which is
 * the point rather than a workaround. There are two defences here, and this
 * shows both: the trigger means no amount of application-level access can edit
 * the trail — you need DDL privilege on the table to even attempt it — and the
 * hash chain means that succeeding at that still does not get you an
 * undetectable edit.
 */
@RestController
@RequestMapping("/api/admin")
@Profile("demo")
@RequiredArgsConstructor
public class TamperController {

    private static final Logger log = LoggerFactory.getLogger(TamperController.class);
    private static final String TRIGGER = "trg_audit_no_update";

    private final JdbcTemplate jdbc;
    private final LedgerVerifier verifier;

    @PostConstruct
    void announce() {
        log.warn("DEMO PROFILE: /api/admin/tamper/{{id}} is ENABLED. "
                + "It can edit the audit ledger and must never be reachable outside a demo.");
    }

    @PostMapping("/tamper/{eventId}")
    public Map<String, Object> tamper(@PathVariable long eventId) {
        log.warn("TAMPER: disabling {} to corrupt audit_event {}", TRIGGER, eventId);
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER " + TRIGGER);

        int updated;
        try {
            updated = jdbc.update("""
                    update audit_event
                       set payload = jsonb_set(payload, '{tamperedBy}', '"demo"'::jsonb, true)
                     where id = ?
                    """, eventId);
        } finally {
            // Always, including on failure. Leaving the ledger mutable after a
            // failed tamper would be a far worse outcome than the tamper itself.
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER " + TRIGGER);
            log.warn("TAMPER: {} re-enabled", TRIGGER);
        }

        if (updated == 0) {
            return Map.of("tampered", false, "reason", "no audit_event with id " + eventId);
        }
        return Map.of("tampered", true, "eventId", eventId,
                "verify", verifier.verifyAll());
    }
}

package dev.capstan.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Every case that did not recover, grouped by whether that was the right answer.
 *
 * <p>The grouping is the point. "Correctly abandoned" and "correctly escalated"
 * are not failures — a bounded system is supposed to stop — and lumping them in
 * with real misses would understate the result as badly as omitting them would
 * overstate it. Group 3 is the honest list: cases the oracle says were
 * recoverable and Capstan did not recover. It gets published in full.
 */
@Service
@RequiredArgsConstructor
public class ExceptionList {

    private final JdbcClient jdbc;

    public record Exception(UUID caseId, long amountPaise, String diagnosedCause, String trueCause,
                            String terminalStatus, String terminalReason, int ladderPosition,
                            String stoppedByGuardrail, String nextStepForAHuman) {
    }

    public Map<String, Object> forBatch(String batch, boolean includeTrueCause) {
        List<Exception> all = load(batch, includeTrueCause);

        List<Exception> abandoned = new ArrayList<>();
        List<Exception> escalated = new ArrayList<>();
        List<Exception> missed = new ArrayList<>();
        List<Exception> misdiagnosed = new ArrayList<>();

        for (Exception e : all) {
            boolean recoverable = recoverable(e.caseId());
            if (!recoverable) {
                abandoned.add(e);
            } else if ("ESCALATED".equals(e.terminalStatus())) {
                escalated.add(e);
            } else {
                missed.add(e);
            }
            if (e.trueCause() != null && !e.trueCause().equals(e.diagnosedCause()) && recoverable) {
                misdiagnosed.add(e);
            }
        }

        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("correctlyAbandoned", abandoned);
        groups.put("correctlyEscalated", escalated);
        groups.put("missed", missed);
        groups.put("misdiagnosed", misdiagnosed);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", batch);
        body.put("notRecovered", all.size());
        body.put("counts", Map.of(
                "correctlyAbandoned", abandoned.size(),
                "correctlyEscalated", escalated.size(),
                "missed", missed.size(),
                "misdiagnosed", misdiagnosed.size()));
        body.put("groups", groups);
        return body;
    }

    private boolean recoverable(UUID caseId) {
        Boolean value = jdbc.sql("select recoverable from case_oracle where case_id = :id")
                .param("id", caseId).query(Boolean.class).optional().orElse(false);
        return Boolean.TRUE.equals(value);
    }

    private List<Exception> load(String batch, boolean includeTrueCause) {
        return jdbc.sql("""
                select c.id, c.amount_paise, c.diagnosed_cause, o.true_cause,
                       c.status, coalesce(c.terminal_reason, '') as terminal_reason,
                       (select count(*) from intervention i where i.case_id = c.id) as ladder_position,
                       (select e.payload->>'guardrailId' from audit_event e
                         where e.case_id = c.id
                           and e.event_type in ('GUARDRAIL_BLOCKED', 'COMMS_SUPPRESSED')
                         order by e.seq desc limit 1) as guardrail
                  from recovery_case c
                  left join case_oracle o on o.case_id = c.id
                 where c.batch_label = :label and c.status <> 'RECOVERED'
                 order by c.amount_paise desc
                """)
                .param("label", batch)
                .query((rs, rowNum) -> {
                    String status = rs.getString("status");
                    String guardrail = rs.getString("guardrail");
                    return new Exception(
                            rs.getObject("id", UUID.class),
                            rs.getLong("amount_paise"),
                            rs.getString("diagnosed_cause"),
                            includeTrueCause ? rs.getString("true_cause") : null,
                            status,
                            rs.getString("terminal_reason"),
                            rs.getInt("ladder_position"),
                            guardrail,
                            nextStep(status, guardrail));
                })
                .list();
    }

    private static String nextStep(String status, String guardrail) {
        return switch (status == null ? "" : status) {
            case "ESCALATED" -> "Confirm with the bank whether the customer was charged, "
                    + "then close or refund.";
            case "ABANDONED" -> "No automated action remains. Contact the customer directly "
                    + "or write the amount off.";
            case "EXPIRED" -> "The billing cycle closed. Roll this into the next cycle's invoice.";
            case "IN_FLIGHT" -> "An attempt is still unresolved. Wait for reconciliation "
                    + "before doing anything.";
            default -> guardrail != null
                    ? "Blocked by " + guardrail + ". Review whether that bound should apply here."
                    : "Review manually.";
        };
    }
}

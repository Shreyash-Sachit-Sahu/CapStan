package dev.capstan.backtest;

import dev.capstan.audit.AuditLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Loads a simulator fixture into the domain tables.
 *
 * <p>This class lives in {@code dev.capstan.backtest} rather than an ingest or
 * admin package for one reason: it writes {@code case_oracle} rows, and
 * OracleIsolationTest fails the build if the oracle is named anywhere else. The
 * endpoint URL stays under /api/admin; only the package is constrained.
 *
 * <p>Cause fields on recovery_case are left NULL on purpose. Phase 03 fills them.
 */
@Service
@RequiredArgsConstructor
public class BatchLoader {

    private final JdbcClient jdbc;
    private final AuditLedger ledger;
    private final Clock clock;

    @Transactional
    public Result load(List<BatchRecord> batch, String batchLabel) {
        for (BatchRecord record : batch) {
            insertCustomer(record.customer());
            insertMandate(record.mandate());
            insertCase(record.caseData(), batchLabel);
            insertOracle(record.caseData().id(), record.oracle());
        }
        openCasesAfterCommit(batch, batchLabel);
        long atRiskPaise = batch.stream().mapToLong(r -> r.caseData().amountPaise()).sum();
        return new Result(batch.size(), atRiskPaise);
    }

    /**
     * CASE_OPENED has to wait for this transaction to commit.
     *
     * <p>Audit writes are {@code REQUIRES_NEW}, which is the whole point of them
     * — they must outlive a rollback of the work they describe. But that means
     * they run in a <i>separate</i> transaction, and {@code audit_event.case_id}
     * is a foreign key: from that transaction the case rows this one just
     * inserted do not exist yet, and every insert would fail on the constraint.
     *
     * <p>So the events are registered and fired after commit. If there is no
     * transaction to wait for, they are written immediately.
     */
    private void openCasesAfterCommit(List<BatchRecord> batch, String batchLabel) {
        Runnable emit = () -> {
            Instant at = Instant.now(clock);
            for (BatchRecord record : batch) {
                ledger.record(record.caseData().id(), AuditLedger.ACTOR_LOADER,
                        AuditLedger.CASE_OPENED,
                        Map.of("batchLabel", batchLabel,
                                "amountPaise", record.caseData().amountPaise(),
                                "customerRef", String.valueOf(record.customer().externalRef()),
                                "rawErrorReason",
                                String.valueOf(record.caseData().rawErrorReason())), at);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emit.run();
                        }
                    });
        } else {
            emit.run();
        }
    }

    private void insertCustomer(BatchRecord.Customer customer) {
        jdbc.sql("""
                insert into merchant_customer (id, external_ref, contact_opted_out, risk_flagged, preferred_locale)
                values (:id, :externalRef, :contactOptedOut, :riskFlagged, :preferredLocale)
                """)
                .param("id", customer.id())
                .param("externalRef", customer.externalRef())
                .param("contactOptedOut", customer.contactOptedOut())
                .param("riskFlagged", customer.riskFlagged())
                .param("preferredLocale", customer.preferredLocale())
                .update();
    }

    private void insertMandate(BatchRecord.Mandate mandate) {
        jdbc.sql("""
                insert into mandate (id, customer_id, rail, valid_from, valid_until,
                                     max_amount_paise, status, alternate_rail)
                values (:id, :customerId, :rail, :validFrom, :validUntil,
                        :maxAmountPaise, :status, :alternateRail)
                """)
                .param("id", mandate.id())
                .param("customerId", mandate.customerId())
                .param("rail", mandate.rail())
                .param("validFrom", at(mandate.validFrom()))
                .param("validUntil", at(mandate.validUntil()))
                .param("maxAmountPaise", mandate.maxAmountPaise())
                .param("status", mandate.status())
                .param("alternateRail", mandate.alternateRail())
                .update();
    }

    private void insertCase(BatchRecord.Case caseData, String batchLabel) {
        jdbc.sql("""
                insert into recovery_case (id, mandate_id, customer_id, amount_paise, currency,
                                           billing_cycle_end, first_failed_at, status,
                                           raw_error_code, raw_error_reason, raw_error_desc,
                                           raw_error_source, raw_error_step, bank_narration,
                                           batch_label, created_at, updated_at)
                values (:id, :mandateId, :customerId, :amountPaise, :currency,
                        :billingCycleEnd, :firstFailedAt, 'OPEN',
                        :rawErrorCode, :rawErrorReason, :rawErrorDesc,
                        :rawErrorSource, :rawErrorStep, :bankNarration,
                        :batchLabel, :now, :now)
                """)
                .param("batchLabel", batchLabel)
                .param("now", at(Instant.now(clock)))
                .param("rawErrorReason", caseData.rawErrorReason())
                .param("id", caseData.id())
                .param("mandateId", caseData.mandateId())
                .param("customerId", caseData.customerId())
                .param("amountPaise", caseData.amountPaise())
                .param("currency", caseData.currency())
                .param("billingCycleEnd", at(caseData.billingCycleEnd()))
                .param("firstFailedAt", at(caseData.firstFailedAt()))
                .param("rawErrorCode", caseData.rawErrorCode())
                .param("rawErrorDesc", caseData.rawErrorDesc())
                .param("rawErrorSource", caseData.rawErrorSource())
                .param("rawErrorStep", caseData.rawErrorStep())
                .param("bankNarration", caseData.bankNarration())
                .update();
    }

    private void insertOracle(java.util.UUID caseId, BatchRecord.Oracle oracle) {
        jdbc.sql("""
                insert into case_oracle (case_id, recoverable, recovery_window_start,
                                         required_channel, nudge_sensitivity,
                                         attempt_success_prob, true_cause, true_state)
                values (:caseId, :recoverable, :recoveryWindowStart,
                        :requiredChannel, :nudgeSensitivity,
                        :attemptSuccessProb, :trueCause, :trueState)
                """)
                .param("caseId", caseId)
                .param("recoverable", oracle.recoverable())
                .param("recoveryWindowStart", at(oracle.recoveryWindowStart()))
                .param("requiredChannel", oracle.requiredChannel())
                .param("nudgeSensitivity", oracle.nudgeSensitivity())
                .param("attemptSuccessProb", oracle.attemptSuccessProb())
                .param("trueCause", oracle.trueCause())
                .param("trueState", oracle.trueState())
                .update();
    }

    /** pgjdbc binds OffsetDateTime to TIMESTAMPTZ without ambiguity; Instant is less certain. */
    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public record Result(int loaded, long atRiskPaise) {
    }
}

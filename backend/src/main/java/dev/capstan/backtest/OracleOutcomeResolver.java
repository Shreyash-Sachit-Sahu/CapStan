package dev.capstan.backtest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one narrow way anything outside this package can reach ground truth.
 *
 * <p>{@code SimulatedGateway} stands in for reality, and reality knows whether
 * a debit would have worked. The <i>agent</i> still does not: diagnosis and the
 * policy engine hold no reference to this type, and
 * {@code OracleIsolationTest} fails the build if {@code CaseOracle} or
 * {@code case_oracle} is named anywhere outside {@code dev.capstan.backtest}.
 * Keeping the lookup here is what lets the gateway consult truth without the
 * rest of the system being able to.
 *
 * <p>A concrete class rather than an interface with one implementation: the
 * isolation this provides is a package boundary, and the boundary holds
 * whether or not there is an interface in front of it.
 */
@Component
@RequiredArgsConstructor
public class OracleOutcomeResolver {

    private final JdbcClient jdbc;

    /**
     * @param requiredChannel ANY | SAME_RAIL | ALTERNATE_RAIL | REAUTH_REQUIRED
     */
    public record Truth(boolean recoverable, Instant recoveryWindowStart, String requiredChannel,
                        double nudgeSensitivity, double attemptSuccessProb, String trueCause) {
    }

    public Optional<Truth> truthFor(UUID caseId) {
        return jdbc.sql("""
                select recoverable, recovery_window_start, required_channel,
                       nudge_sensitivity, attempt_success_prob, true_cause
                  from case_oracle
                 where case_id = :id
                """)
                .param("id", caseId)
                .query((ResultSet rs, int rowNum) -> map(rs))
                .optional();
    }

    private static Truth map(ResultSet rs) throws SQLException {
        OffsetDateTime window = rs.getObject("recovery_window_start", OffsetDateTime.class);
        return new Truth(
                rs.getBoolean("recoverable"),
                window == null ? null : window.toInstant(),
                rs.getString("required_channel"),
                rs.getDouble("nudge_sensitivity"),
                rs.getDouble("attempt_success_prob"),
                rs.getString("true_cause"));
    }
}

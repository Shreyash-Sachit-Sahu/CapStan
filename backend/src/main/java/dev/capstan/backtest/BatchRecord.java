package dev.capstan.backtest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * One element of a simulator fixture: the customer, the mandate, the failed
 * case, and the hidden oracle for that case.
 *
 * <p>Snake_case is applied per nested record rather than globally, because
 * Phase 04's DecisionRecord is specified in camelCase and a global naming
 * strategy would silently reshape it.
 */
public record BatchRecord(
        @NotNull @Valid Customer customer,
        @NotNull @Valid Mandate mandate,
        @NotNull @Valid @JsonProperty("case") Case caseData,
        @NotNull @Valid Oracle oracle) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Customer(
            @NotNull UUID id,
            @NotBlank String externalRef,
            boolean contactOptedOut,
            boolean riskFlagged,
            @NotBlank String preferredLocale) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Mandate(
            @NotNull UUID id,
            @NotNull UUID customerId,
            @NotBlank String rail,
            @NotNull Instant validFrom,
            @NotNull Instant validUntil,
            @Positive long maxAmountPaise,
            @NotBlank String status,
            String alternateRail) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Case(
            @NotNull UUID id,
            @NotNull UUID mandateId,
            @NotNull UUID customerId,
            @Positive long amountPaise,
            @NotBlank String currency,
            @NotNull Instant billingCycleEnd,
            @NotNull Instant firstFailedAt,
            @NotBlank String rawErrorCode,
            @NotBlank String rawErrorReason,
            String rawErrorDesc,
            String rawErrorSource,
            String rawErrorStep,
            String bankNarration) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Oracle(
            boolean recoverable,
            Instant recoveryWindowStart,
            @NotBlank String requiredChannel,
            double nudgeSensitivity,
            double attemptSuccessProb,
            @NotBlank String trueCause,
            String trueState) {
    }
}

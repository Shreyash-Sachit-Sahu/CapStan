package dev.capstan.gateway;

import java.time.Instant;
import java.util.UUID;

/**
 * The seam between Capstan and the thing that actually moves money.
 *
 * <p>Two implementations: {@link RazorpayTestGateway} against Razorpay's real
 * test mode, and {@link SimulatedGateway} for the backtest.
 *
 * <p>{@code UNKNOWN} is a first-class result, not an error. A gateway call that
 * neither confirms nor denies is the ordinary case this whole phase exists to
 * handle, and every implementation must be able to say it.
 */
public interface PaymentGateway {

    AttemptResult debit(DebitCommand cmd);

    /** Reconciliation: what actually happened to the attempt under this key. */
    AttemptResult fetchByIdempotencyKey(String key);

    void sendCommunication(CommsCommand cmd);

    /**
     * @param alternateRail true when this debit is a RAIL_SWITCH onto the
     *                      instrument the mandate lists as its alternate. The
     *                      gateway needs it because whether a given rail can
     *                      succeed is a property of the customer's situation,
     *                      not of the rail string.
     */
    record DebitCommand(UUID caseId, UUID interventionId, String idempotencyKey,
                        String rail, boolean alternateRail, long amountPaise, Instant at) {
    }

    record CommsCommand(UUID caseId, String kind, String locale, String text, Instant at) {
    }

    enum Outcome { SUCCEEDED, FAILED, UNKNOWN }

    record AttemptResult(Outcome outcome, String gatewayRef, String reason) {

        public static AttemptResult succeeded(String ref) {
            return new AttemptResult(Outcome.SUCCEEDED, ref, null);
        }

        public static AttemptResult failed(String ref, String reason) {
            return new AttemptResult(Outcome.FAILED, ref, reason);
        }

        public static AttemptResult unknown(String reason) {
            return new AttemptResult(Outcome.UNKNOWN, null, reason);
        }

        /** No record of this key at the gateway -- the debit never landed. */
        public static AttemptResult absent() {
            return new AttemptResult(Outcome.FAILED, null, "no attempt found for key");
        }
    }
}

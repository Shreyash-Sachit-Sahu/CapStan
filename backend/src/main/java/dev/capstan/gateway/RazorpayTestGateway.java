package dev.capstan.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Razorpay test mode, so "runs against Razorpay APIs" is literally true.
 *
 * <h2>On idempotency</h2>
 *
 * <p>Razorpay does not accept an idempotency header on payment creation. The
 * only idempotency header it documents is {@code X-Payout-Idempotency}, and
 * that is RazorpayX Payouts and the Composite APIs, not this flow. Sending an
 * invented {@code X-Idempotency-Key} would be worse than sending nothing: the
 * gateway would ignore it while our own documentation implied it was being
 * honoured upstream.
 *
 * <p>So exactly-once here is <b>ours</b>. {@code uq_idem} on
 * {@code payment_attempt} is what actually enforces it, and that is the
 * stronger claim anyway -- the guarantee does not depend on the gateway's
 * cooperation.
 *
 * <p>What Razorpay does give us is a reconciliation handle. An order carries a
 * merchant-supplied {@code receipt}, and {@code GET /v1/orders?receipt=} fetches
 * by it, which is how {@link #fetchByIdempotencyKey} answers after a lost
 * response. Receipts cap at 40 characters and the key is 44, so the
 * {@code cap_} prefix comes off; the remaining 40 hex characters are still a
 * bijection with the key.
 *
 * <h2>What is verified</h2>
 *
 * <p>Order creation and the receipt lookup are real calls against test mode.
 * The recurring charge itself needs a tokenised mandate created through a
 * checkout flow, which cannot be done headlessly, so {@link #debit} will report
 * {@code FAILED} on the charge leg until a real token exists. Nothing in this
 * class has been executed against live credentials.
 */
@Component
@Profile("razorpay")
public class RazorpayTestGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayTestGateway.class);
    private static final String BASE = "https://api.razorpay.com/v1";

    private final RestClient http;

    public RazorpayTestGateway(@Value("${capstan.razorpay.key-id:}") String keyId,
                               @Value("${capstan.razorpay.key-secret:}") String keySecret,
                               @Value("${capstan.razorpay.timeout-ms:20000}") long timeoutMs) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "profile 'razorpay' is active but capstan.razorpay.key-id/key-secret are unset");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        String basic = Base64.getEncoder().encodeToString(
                (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .baseUrl(BASE)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Razorpay receipts cap at 40 chars; the key is "cap_" + 40 hex. */
    private static String receipt(String idempotencyKey) {
        return idempotencyKey.startsWith("cap_") ? idempotencyKey.substring(4) : idempotencyKey;
    }

    @Override
    public AttemptResult debit(DebitCommand cmd) {
        try {
            JsonNode order = http.post().uri("/orders")
                    .body(Map.of(
                            "amount", cmd.amountPaise(),
                            "currency", "INR",
                            "receipt", receipt(cmd.idempotencyKey()),
                            "payment_capture", true,
                            "notes", Map.of("capstan_case", cmd.caseId().toString(),
                                    "capstan_key", cmd.idempotencyKey())))
                    .retrieve().body(JsonNode.class);

            String orderId = order == null ? null : order.path("id").asText(null);
            if (orderId == null) {
                return AttemptResult.unknown("order created but no id returned");
            }
            // The charge leg needs a tokenised mandate from a checkout flow.
            // Until one exists this is a clean, honest failure rather than a
            // fabricated success.
            log.warn("Razorpay order {} created; no mandate token available to charge", orderId);
            return AttemptResult.failed(orderId, "no_mandate_token");
        } catch (Exception e) {
            // Never collapse an unclear gateway response into FAILED. FAILED is a
            // permission -- it authorises the next debit -- and we have not
            // established that anything failed.
            log.warn("Razorpay debit outcome unclear for case {}: {}", cmd.caseId(), e.toString());
            return AttemptResult.unknown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public AttemptResult fetchByIdempotencyKey(String key) {
        try {
            JsonNode orders = http.get()
                    .uri(uri -> uri.path("/orders").queryParam("receipt", receipt(key)).build())
                    .retrieve().body(JsonNode.class);

            JsonNode items = orders == null ? null : orders.path("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return AttemptResult.absent();
            }
            JsonNode order = items.get(0);
            String orderId = order.path("id").asText(null);
            // "paid" is Razorpay's terminal state for an order whose payment was
            // captured. "attempted" means a payment exists but did not complete.
            return switch (order.path("status").asText("")) {
                case "paid" -> AttemptResult.succeeded(orderId);
                case "attempted", "created" -> AttemptResult.failed(orderId, "order_not_paid");
                default -> AttemptResult.unknown("unrecognised order status");
            };
        } catch (Exception e) {
            return AttemptResult.unknown("reconcile lookup failed: " + e.getMessage());
        }
    }

    @Override
    public void sendCommunication(CommsCommand cmd) {
        // Capstan does not own a messaging provider, and wiring one to send real
        // SMS during a demo is a liability, not a feature.
        log.info("comms[{}] case={} locale={} text={}",
                cmd.kind(), cmd.caseId(), cmd.locale(), cmd.text());
    }
}

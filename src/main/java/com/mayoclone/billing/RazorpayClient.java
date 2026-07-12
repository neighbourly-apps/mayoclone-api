package com.mayoclone.billing;

import com.mayoclone.util.TimeoutRestClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Thin Razorpay Orders API client built on Spring's {@link RestClient} (HTTP Basic
 * auth = key-id:key-secret). Every call here is a REAL Razorpay HTTP request and is
 * therefore NEVER exercised by the build — tests drive the checkout/verify logic
 * without hitting Razorpay (checkout returns 503 when keys are absent). To go live,
 * set {@code RAZORPAY_KEY_ID} + {@code RAZORPAY_KEY_SECRET}.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);
    private static final String ORDERS_URL = "https://api.razorpay.com/v1/orders";

    // Timeouts (connect 3s / read 10s) so a wedged Razorpay socket cannot hang the
    // checkout servlet thread forever. Bare RestClient.create() has NO timeouts.
    private final RestClient http = TimeoutRestClients.create();

    /** @return the created order's id (Razorpay {@code id} field). */
    @SuppressWarnings("unchecked")
    public String createOrder(String keyId, String keySecret, long amount, String currency,
                              String receipt, Map<String, Object> notes) {
        String basic = Base64.getEncoder().encodeToString(
                (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = Map.of(
                "amount", amount,
                "currency", currency,
                "receipt", receipt,
                "notes", notes);
        Map<String, Object> res = http.post()
                .uri(ORDERS_URL)
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (res == null || res.get("id") == null) {
            throw new IllegalStateException("Razorpay order creation returned no id");
        }
        String orderId = String.valueOf(res.get("id"));
        log.info("Created Razorpay order {}", orderId);
        return orderId;
    }
}

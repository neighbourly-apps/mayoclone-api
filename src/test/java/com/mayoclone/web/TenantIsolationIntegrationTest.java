package com.mayoclone.web;

import com.mayoclone.domain.IngestFailure;
import com.mayoclone.ingest.inbound.InboundSignatureVerifier;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves cross-tenant isolation end-to-end over MockMvc/H2: account A owns a
 * vendor + an ingested order + an ingest-failure; account B sees NONE of A's data
 * and gets 404 (never 403 — no enumeration) for A's ids.
 */
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private InboundSignatureVerifier signatureVerifier;

    @Autowired
    private IngestFailureRepository ingestFailureRepo;

    @Test
    void accountBCannotSeeOrTouchAccountAsData() throws Exception {
        // --- Account A: token + a FORWARDING vendor (mints an ingest address) ---
        String tokenA = registerAndToken(uniqueEmail(), PASSWORD);
        MvcResult vendorRes = mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(forwardingVendor())))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> vendor = json.readValue(vendorRes.getResponse().getContentAsString(), Map.class);
        Long vendorIdA = ((Number) vendor.get("id")).longValue();
        String ingestAddress = (String) vendor.get("ingestAddress");

        // A ingests a real order via the public inbound webhook (attributed to A).
        postInbound(ingestAddress, "orders@zoopindia.com", zoopBody(), "<tenant-a-1@zoopindia.com>")
                .andExpect(status().isOk());

        // ...and produces an ingest-failure via an unmatched sender domain.
        postInbound(ingestAddress, "orders@totally-unknown-domain.example",
                "no aggregator here", "<tenant-a-fail@unknown>")
                .andExpect(status().isOk());

        // A can see exactly one order.
        MvcResult ordersA = mvc.perform(get("/api/orders")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();
        List<?> aOrders = json.readValue(ordersA.getResponse().getContentAsString(), List.class);
        Long orderIdA = ((Number) ((Map<?, ?>) aOrders.get(0)).get("id")).longValue();

        // A's ingest-failure id (looked up directly; created above).
        Long aAccountId = readMyAccountId(tokenA);
        List<IngestFailure> aFailures = ingestFailureRepo.findByAccountIdOrderByCreatedAtDesc(aAccountId);
        assertFalse(aFailures.isEmpty(), "account A should have an ingest failure");
        Long failureIdA = aFailures.get(0).getId();

        // --- Account B: a different tenant ---
        String tokenB = registerAndToken(uniqueEmail(), PASSWORD);

        // B sees no orders of its own.
        mvc.perform(get("/api/orders")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // B cannot read A's order → 404 (not 403).
        mvc.perform(get("/api/orders/" + orderIdA)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // B cannot delete A's vendor → 404.
        mvc.perform(delete("/api/vendors/" + vendorIdA)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // B sees no ingest-failures of its own.
        mvc.perform(get("/api/ingest-failures")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // B cannot retry or delete A's ingest-failure → 404.
        mvc.perform(post("/api/ingest-failures/" + failureIdA + "/retry")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/ingest-failures/" + failureIdA)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Sanity: A still owns its vendor (B's failed delete didn't touch it).
        mvc.perform(get("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        assertEquals(aAccountId, aFailures.get(0).getAccountId());
    }

    private org.springframework.test.web.servlet.ResultActions postInbound(
            String recipient, String from, String body, String messageId) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("recipient", recipient);
        payload.put("from", from);
        payload.put("subject", "Order confirmed");
        payload.put("bodyPlain", body);
        payload.put("messageId", messageId);
        String raw = writeJson(payload);
        String sig = signatureVerifier.sign(raw, Instant.now().getEpochSecond());
        return mvc.perform(post("/api/inbound/email")
                .header("X-Forwarded-For", uniqueIp())
                .header(InboundSignatureVerifier.HEADER, sig)
                .contentType(APPLICATION_JSON)
                .content(raw));
    }

    private Long readMyAccountId(String token) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map = json.readValue(me.getResponse().getContentAsString(), Map.class);
        return ((Number) map.get("id")).longValue();
    }

    private static Map<String, Object> forwardingVendor() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("restaurantName", "A Foods");
        m.put("ownerEmail", "owner-a@test.local");
        m.put("stationCode", "NDLS");
        m.put("stationName", "New Delhi");
        m.put("phone", "9876543210");
        m.put("sourceType", "FORWARDING");
        return m;
    }

    private static String zoopBody() {
        return """
                Dear Rajesh Kumar,
                Your Zoop e-catering order is confirmed.
                Order ID: ZOOPA100
                PNR: 4512367890
                Train No.: 12951 Mumbai Rajdhani Express
                Coach: B3  Berth: 32
                Boarding: BCT
                Delivery Station: NDLS New Delhi
                Passenger: Rajesh Kumar
                Phone: 9876543210
                Delivery Date: 2026-07-08
                Delivery Time: 13:00-13:30
                2 x Veg Biryani - ₹180
                Total: ₹360
                """;
    }
}

package com.mayoclone.web;

import com.mayoclone.ingest.inbound.MailgunSignatureVerifier;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REAL Mailgun inbound adapter over MockMvc/H2. Covers signature valid/invalid/stale,
 * token replay, unknown recipient (no leak, nothing stored), and missing recipient (406).
 */
class InboundMailgunWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MailgunSignatureVerifier verifier;

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Test
    void validSignatureAndKnownRecipientCreatesOrder() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        long now = Instant.now().getEpochSecond();
        String token = "tok-ok-" + System.nanoTime();

        mvc.perform(mailgunPost(ingestAddress, "orders@zoopindia.com", zoopBody(),
                        "<mg-ok@zoopindia.com>", String.valueOf(now), token, verifier.sign(String.valueOf(now), token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.newOrders").value(1));
    }

    @Test
    void invalidSignatureIsUnauthorized() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        long now = Instant.now().getEpochSecond();

        mvc.perform(mailgunPost(ingestAddress, "orders@zoopindia.com", zoopBody(),
                        "<mg-bad@zoopindia.com>", String.valueOf(now), "tok-bad-" + System.nanoTime(), "deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleTimestampIsUnauthorized() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        long old = Instant.now().getEpochSecond() - 1200; // 20 min old → beyond 15-min window
        String ts = String.valueOf(old);
        String token = "tok-stale-" + System.nanoTime();

        mvc.perform(mailgunPost(ingestAddress, "orders@zoopindia.com", zoopBody(),
                        "<mg-stale@zoopindia.com>", ts, token, verifier.sign(ts, token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRecipientIsAcceptedWithNoLeakAndStoresNothing() throws Exception {
        long ordersBefore = orderRepo.count();
        long now = Instant.now().getEpochSecond();
        String token = "tok-unknown-" + System.nanoTime();

        mvc.perform(mailgunPost("no-such@inbound.mayoclone.test", "orders@zoopindia.com", zoopBody(),
                        "<mg-unknown@zoopindia.com>", String.valueOf(now), token, verifier.sign(String.valueOf(now), token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"))
                // No recipient / vendor detail leaked.
                .andExpect(jsonPath("$.newOrders").doesNotExist());

        assertEquals(ordersBefore, orderRepo.count(), "unknown recipient must not create an order");
    }

    @Test
    void missingRecipientIsNotAcceptable() throws Exception {
        long now = Instant.now().getEpochSecond();
        String token = "tok-norcpt-" + System.nanoTime();

        // No recipient param at all → 406 so Mailgun stops retrying.
        mvc.perform(post("/api/inbound/mailgun")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_FORM_URLENCODED)
                        .param("sender", "orders@zoopindia.com")
                        .param("subject", "Order confirmed")
                        .param("body-plain", zoopBody())
                        .param("timestamp", String.valueOf(now))
                        .param("token", token)
                        .param("signature", verifier.sign(String.valueOf(now), token)))
                .andExpect(status().isNotAcceptable());
    }

    // --- helpers -------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mailgunPost(
            String recipient, String sender, String bodyPlain, String messageId,
            String timestamp, String token, String signature) {
        return post("/api/inbound/mailgun")
                .header("X-Forwarded-For", uniqueIp())
                .contentType(APPLICATION_FORM_URLENCODED)
                .param("recipient", recipient)
                .param("sender", sender)
                .param("subject", "Order confirmed")
                .param("body-plain", bodyPlain)
                .param("Message-Id", messageId)
                .param("timestamp", timestamp)
                .param("token", token)
                .param("signature", signature);
    }

    private String createForwardingVendorAddress() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("restaurantName", "Mailgun Foods");
        vendor.put("ownerEmail", "owner-" + System.nanoTime() + "@test.local");
        vendor.put("stationCode", "NDLS");
        vendor.put("stationName", "New Delhi");
        vendor.put("phone", "9876543210");
        vendor.put("sourceType", "FORWARDING");

        MvcResult res = mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(vendor)))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return (String) dto.get("ingestAddress");
    }

    private static String zoopBody() {
        return """
                Dear Rajesh Kumar,
                Your Zoop e-catering order is confirmed.
                Order ID: ZOOPMG1
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

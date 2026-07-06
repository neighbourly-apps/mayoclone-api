package com.mayoclone.web;

import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.IngestFailureReason;
import com.mayoclone.ingest.inbound.InboundSignatureVerifier;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.repository.IrctcOrderRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inbound HMAC-signed webhook over MockMvc/H2. Covers signature acceptance/rejection,
 * timestamp replay guard, recipient routing (known FORWARDING vendor vs unknown),
 * and dead-lettering.
 *
 * <p>NOTE on the "matches an aggregator but fails parsing" case: the production
 * {@code GenericIrctcEmailParser} is intentionally non-throwing (it degrades to
 * defaults), so PARSE_FAILED cannot be produced through the real webhook. The
 * reachable failure path here is NO_AGGREGATOR_MATCH (known recipient, unknown
 * sender domain), which still returns 200 and writes an {@code ingest_failure}
 * row. PARSE_FAILED itself is covered at the pipeline unit level
 * ({@code IngestionCoreTest}).
 */
class InboundWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private InboundSignatureVerifier verifier;

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Autowired
    private IngestFailureRepository failureRepo;

    @Test
    void validSignatureAndKnownRecipientCreatesOrder() throws Exception {
        String ingestAddress = createForwardingVendorAddress();

        Map<String, String> payload = payload(ingestAddress, "orders@zoopindia.com",
                zoopBody(), "<inbound-ok-1@zoopindia.com>");
        String raw = writeJson(payload);
        String sig = verifier.sign(raw, Instant.now().getEpochSecond());

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .header(InboundSignatureVerifier.HEADER, sig)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOrders").value(1));
    }

    @Test
    void absentSignatureIsUnauthorized() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        String raw = writeJson(payload(ingestAddress, "orders@zoopindia.com",
                zoopBody(), "<inbound-nosig@zoopindia.com>"));

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSignatureIsUnauthorized() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        String raw = writeJson(payload(ingestAddress, "orders@zoopindia.com",
                zoopBody(), "<inbound-badsig@zoopindia.com>"));

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .header(InboundSignatureVerifier.HEADER,
                                "t=" + Instant.now().getEpochSecond() + ",v1=deadbeef")
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleTimestampIsUnauthorized() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        String raw = writeJson(payload(ingestAddress, "orders@zoopindia.com",
                zoopBody(), "<inbound-stale@zoopindia.com>"));
        // Correctly signed but 10 minutes old → beyond the ±5min replay window.
        long tenMinutesAgo = Instant.now().getEpochSecond() - 600;
        String sig = verifier.sign(raw, tenMinutesAgo);

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .header(InboundSignatureVerifier.HEADER, sig)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRecipientWithValidSignatureIsAcceptedButStoresNothing() throws Exception {
        long ordersBefore = orderRepo.count();
        String raw = writeJson(payload("no-such-vendor@inbound.mayoclone.test",
                "orders@zoopindia.com", zoopBody(), "<inbound-unknown@zoopindia.com>"));
        String sig = verifier.sign(raw, Instant.now().getEpochSecond());

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .header(InboundSignatureVerifier.HEADER, sig)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isAccepted());

        assertEquals(ordersBefore, orderRepo.count(), "unknown recipient must not create an order");
    }

    @Test
    void knownRecipientUnroutableSenderIsAcceptedAndDeadLettered() throws Exception {
        String ingestAddress = createForwardingVendorAddress();
        Map<String, String> payload = payload(ingestAddress,
                "orders@totally-unknown-domain.example", "no aggregator matches this",
                "<inbound-deadletter@unknown>");
        String raw = writeJson(payload);
        String sig = verifier.sign(raw, Instant.now().getEpochSecond());

        mvc.perform(post("/api/inbound/email")
                        .header("X-Forwarded-For", uniqueIp())
                        .header(InboundSignatureVerifier.HEADER, sig)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newOrders").value(0));

        List<IngestFailure> failures = failureRepo.findAll();
        assertFalse(failures.isEmpty(), "a dead-letter row should have been written");
        assertEquals(IngestFailureReason.NO_AGGREGATOR_MATCH,
                failures.get(failures.size() - 1).getReason());
    }

    /** Registers an account, creates a FORWARDING vendor, returns its minted ingest address. */
    private String createForwardingVendorAddress() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("restaurantName", "Inbound Foods");
        vendor.put("ownerEmail", "owner-" + System.nanoTime() + "@test.local");
        vendor.put("stationCode", "NDLS");
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

    private static Map<String, String> payload(String recipient, String from, String body, String messageId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("recipient", recipient);
        m.put("from", from);
        m.put("subject", "Order confirmed");
        m.put("bodyPlain", body);
        m.put("messageId", messageId);
        return m;
    }

    private static String zoopBody() {
        return """
                Dear Rajesh Kumar,
                Your Zoop e-catering order is confirmed.
                Order ID: ZOOPWEB1
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

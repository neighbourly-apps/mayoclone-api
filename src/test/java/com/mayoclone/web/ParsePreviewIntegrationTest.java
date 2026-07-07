package com.mayoclone.web;

import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/dev/parse-preview}: authenticated dry-run of route+parse that
 * NEVER persists. Covers the matched/wouldPersist/warnings shape, the raw-RFC822
 * path, the unmatched-sender warning, auth enforcement, and no-DB-write.
 */
class ParsePreviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Test
    void previewMatchesAggregatorAndReportsWouldPersistWithoutWriting() throws Exception {
        String token = registerAndToken(uniqueEmail(), "correct-horse-battery");
        long before = orderRepo.count();

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("from", "orders@zoopindia.com");
        req.put("subject", "Order confirmed");
        req.put("body", zoopBody());

        mvc.perform(post("/api/dev/parse-preview")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedAggregator.code").value("ZOOP"))
                .andExpect(jsonPath("$.wouldPersist").value(true))
                .andExpect(jsonPath("$.parsed.pnr").value("4512367890"))
                .andExpect(jsonPath("$.parsed.trainNumber").value("12951"))
                .andExpect(jsonPath("$.parsed.deliveryStationCode").value("NDLS"))
                .andExpect(jsonPath("$.parsed.items.length()").value(1));

        assertEquals(before, orderRepo.count(), "parse-preview must not write to the DB");
    }

    @Test
    void previewFromRawRfc822DerivesFields() throws Exception {
        String token = registerAndToken(uniqueEmail(), "correct-horse-battery");
        String raw = """
                From: orders@zoopindia.com
                Subject: Confirmed
                Message-ID: <raw-preview@zoopindia.com>
                MIME-Version: 1.0
                Content-Type: text/plain; charset=UTF-8

                Booking ID: ZOOP-RAW-1
                PNR: 4512367890
                Train No.: 12951 Mumbai Rajdhani Express
                Delivery Station: NDLS New Delhi
                2 x Veg Biryani - Rs. 180
                Total: Rs. 360
                """;
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("raw", raw);

        mvc.perform(post("/api/dev/parse-preview")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedAggregator.code").value("ZOOP"))
                .andExpect(jsonPath("$.wouldPersist").value(true))
                .andExpect(jsonPath("$.parsed.externalOrderId").value("ZOOP-RAW-1"));
    }

    @Test
    void previewWarnsWhenNoAggregatorMatches() throws Exception {
        String token = registerAndToken(uniqueEmail(), "correct-horse-battery");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("from", "orders@totally-unknown-domain.example");
        req.put("body", "no aggregator here");

        mvc.perform(post("/api/dev/parse-preview")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedAggregator").doesNotExist())
                .andExpect(jsonPath("$.wouldPersist").value(false))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("no aggregator matched")));
    }

    @Test
    void previewRequiresAuthentication() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("from", "orders@zoopindia.com");
        req.put("body", zoopBody());

        mvc.perform(post("/api/dev/parse-preview")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(req)))
                .andExpect(status().isUnauthorized());
    }

    private static String zoopBody() {
        return """
                Order ID: ZOOPPREV1
                PNR: 4512367890
                Train No.: 12951 Mumbai Rajdhani Express
                Coach: B3  Berth: 32
                Delivery Station: NDLS New Delhi
                Passenger: Rajesh Kumar
                Phone: 9876543210
                Delivery Date: 2026-07-08
                2 x Veg Biryani - ₹180
                Total: ₹360
                """;
    }
}

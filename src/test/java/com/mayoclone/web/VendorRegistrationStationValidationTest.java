package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Station NAME is mandatory when registering a vendor (mailbox). A blank/missing
 * station name is rejected with HTTP 400; a present one is accepted.
 */
class VendorRegistrationStationValidationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    private Map<String, Object> forwardingVendor() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("restaurantName", "Station Foods");
        v.put("ownerEmail", "owner-" + System.nanoTime() + "@test.local");
        v.put("stationCode", "NDLS");
        v.put("phone", "9876543210");
        v.put("sourceType", "FORWARDING");
        return v;
    }

    @Test
    void blankStationName_isRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Map<String, Object> vendor = forwardingVendor();
        vendor.put("stationName", "   "); // blank

        mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(vendor)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingStationName_isRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Map<String, Object> vendor = forwardingVendor(); // no stationName at all

        mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(vendor)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presentStationName_isAccepted() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Map<String, Object> vendor = forwardingVendor();
        vendor.put("stationName", "New Delhi");

        mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(vendor)))
                .andExpect(status().isCreated());
    }
}

package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Train status seam: default provider reports unavailable / source none. */
class TrainStatusIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-train";

    @Test
    void defaultProviderReportsUnavailable() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        mvc.perform(get("/api/trains/12951/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trainNumber").value("12951"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.source").value("none"));
    }

    @Test
    void requiresAuth() throws Exception {
        mvc.perform(get("/api/trains/12951/status")
                        .header("X-Forwarded-For", uniqueIp()))
                .andExpect(status().isUnauthorized());
    }
}

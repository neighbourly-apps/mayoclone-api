package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role/route authorization for the managed-aggregator endpoints: reads are open to
 * any authenticated caller, writes require ROLE_ADMIN, and anonymous access is 401.
 */
class AggregatorAuthorizationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void anonymousGetIsRejected() throws Exception {
        // Anonymous access -> 401 (SecurityConfig installs an HttpStatusEntryPoint so
        // missing auth is 401; authenticated-but-forbidden is 403, asserted below).
        mvc.perform(get("/api/aggregators").header("X-Forwarded-For", uniqueIp()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedGetIsOk() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        mvc.perform(get("/api/aggregators")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdminWritesAreForbidden() throws Exception {
        String ownerToken = registerAndToken(uniqueEmail(), PASSWORD);

        mvc.perform(post("/api/aggregators")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(aggregator("OWNERTRY"))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/aggregators/1")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(aggregator("OWNERTRY"))))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/aggregators/1")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateAggregator() throws Exception {
        String admin = adminToken();
        mvc.perform(post("/api/aggregators")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(aggregator("TESTAGG-" + System.nanoTime()))))
                .andExpect(status().is2xxSuccessful());
    }

    private static Map<String, Object> aggregator(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", "Test Aggregator");
        m.put("senderDomains", List.of("example-agg.com"));
        m.put("brandColor", "#123456");
        return m;
    }
}

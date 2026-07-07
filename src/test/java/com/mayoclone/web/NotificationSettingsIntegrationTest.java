package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notification settings GET (creates defaults) + PUT (upsert). */
class NotificationSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-notif";

    @Test
    void getCreatesDefaultsThenPutUpserts() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        // GET creates defaults: onNewOrder=true, others false, webhookUrl null
        mvc.perform(get("/api/notifications/settings")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").doesNotExist())
                .andExpect(jsonPath("$.onNewOrder").value(true))
                .andExpect(jsonPath("$.onAssigned").value(false))
                .andExpect(jsonPath("$.onCancelled").value(false));

        // PUT upsert
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("webhookUrl", "https://example.test/hook");
        body.put("onNewOrder", false);
        body.put("onAssigned", true);
        body.put("onCancelled", true);
        mvc.perform(put("/api/notifications/settings")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value("https://example.test/hook"))
                .andExpect(jsonPath("$.onNewOrder").value(false))
                .andExpect(jsonPath("$.onAssigned").value(true))
                .andExpect(jsonPath("$.onCancelled").value(true))
                .andExpect(jsonPath("$.updatedAt").exists());

        // GET reflects the upsert (single row per account)
        mvc.perform(get("/api/notifications/settings")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.webhookUrl").value("https://example.test/hook"))
                .andExpect(jsonPath("$.onAssigned").value(true));
    }
}

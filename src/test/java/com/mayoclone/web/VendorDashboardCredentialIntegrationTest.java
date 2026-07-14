package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard-credential API: username+password are encrypted at rest, NEVER returned
 * by any endpoint or DTO, and surface only as a status badge. Delete removes the row.
 */
class VendorDashboardCredentialIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String DASH_USER = "irctc-partner-login";
    private static final String DASH_PASS = "s3cr3t-dashboard-pw";

    @Autowired
    private JdbcTemplate jdbc;

    private Long createImapVendor(String token) throws Exception {
        Map<String, Object> vendor = new LinkedHashMap<>();
        vendor.put("restaurantName", "Enrich Foods");
        vendor.put("ownerEmail", "owner@enrich.local");
        vendor.put("stationName", "New Delhi");
        vendor.put("sourceType", "IMAP");
        vendor.put("imapHost", "imap.enrich.local");
        vendor.put("imapPassword", "imap-pw");
        MvcResult res = mvc.perform(post("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(vendor)))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        // The DTO exposes the dashboard badge as null before any credential is set.
        assertTrue(dto.containsKey("dashboardStatus"));
        assertNull(dto.get("dashboardStatus"));
        return ((Number) dto.get("id")).longValue();
    }

    private String putCredential(String token, Long vendorId, String user, String pass) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", user);
        body.put("password", pass);
        return mvc.perform(put("/api/vendors/" + vendorId + "/dashboard-credential")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void upsertEncryptsSecretsAndNeverReturnsThem() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Long vendorId = createImapVendor(token);

        // PUT credential → response is secret-free (configured + status only).
        String putBody = putCredential(token, vendorId, DASH_USER, DASH_PASS);
        assertTrue(putBody.contains("\"configured\":true"), putBody);
        assertTrue(putBody.contains("NEW"), putBody);
        assertFalseContains(putBody, DASH_USER);
        assertFalseContains(putBody, DASH_PASS);

        // GET credential → same secret-free view.
        mvc.perform(get("/api/vendors/" + vendorId + "/dashboard-credential")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        // Raw DB columns must be CIPHERTEXT (bypass the JPA converter).
        String rawUser = jdbc.queryForObject(
                "SELECT username FROM vendor_dashboard_credential WHERE vendor_id = ?",
                String.class, vendorId);
        String rawPass = jdbc.queryForObject(
                "SELECT password FROM vendor_dashboard_credential WHERE vendor_id = ?",
                String.class, vendorId);
        assertNotEquals(DASH_USER, rawUser, "username must be encrypted at rest");
        assertNotEquals(DASH_PASS, rawPass, "password must be encrypted at rest");
        assertFalseContains(rawUser, DASH_USER);
        assertFalseContains(rawPass, DASH_PASS);

        // The vendor list DTO now surfaces the badge (status), never a secret.
        MvcResult list = mvc.perform(get("/api/vendors")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dashboardStatus").value("NEW"))
                .andReturn();
        assertFalseContains(list.getResponse().getContentAsString(), DASH_PASS);
    }

    @Test
    void deleteRemovesTheCredential() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Long vendorId = createImapVendor(token);
        putCredential(token, vendorId, DASH_USER, DASH_PASS);

        mvc.perform(delete("/api/vendors/" + vendorId + "/dashboard-credential")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/vendors/" + vendorId + "/dashboard-credential")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void missingFields_areRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Long vendorId = createImapVendor(token);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", "only-user");
        mvc.perform(put("/api/vendors/" + vendorId + "/dashboard-credential")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isBadRequest());
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(!haystack.contains(needle), "response/column must not contain the secret: " + needle);
    }
}

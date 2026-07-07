package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email-OTP send/verify over MockMvc on H2 (no Docker). The default test profile
 * leaves {@code mayoclone.auth.otp-dev-mode=true}, so /otp/send returns the
 * plaintext {@code devCode} (this is how the local UI, and these tests, learn the
 * code without SMTP). require-email-otp stays false here, so register is unchanged.
 */
class OtpVerificationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void sendReturns200WithSentTrueAndDevCode() throws Exception {
        mvc.perform(post("/api/auth/otp/send")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", uniqueEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.devCode").isNotEmpty());
    }

    @Test
    void verifyWithCorrectCodeReturnsToken() throws Exception {
        String email = uniqueEmail();
        String code = sendAndReadDevCode(email);

        mvc.perform(post("/api/auth/otp/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void verifyWithWrongCodeReturns400InvalidOrExpired() throws Exception {
        String email = uniqueEmail();
        String code = sendAndReadDevCode(email);

        mvc.perform(post("/api/auth/otp/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email, "code", wrong(code)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_or_expired"));
    }

    @Test
    void verifyForUnknownEmailReturns400() throws Exception {
        mvc.perform(post("/api/auth/otp/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", uniqueEmail(), "code", "123456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_or_expired"));
    }

    @Test
    void tooManyWrongAttemptsReturns429() throws Exception {
        String email = uniqueEmail();
        String code = sendAndReadDevCode(email);
        String bad = wrong(code);
        String ip = uniqueIp();

        // 5 failed attempts each return 400...
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/otp/verify")
                            .header("X-Forwarded-For", ip)
                            .contentType(APPLICATION_JSON)
                            .content(writeJson(Map.of("email", email, "code", bad))))
                    .andExpect(status().isBadRequest());
        }
        // ...the 6th is locked out with 429.
        mvc.perform(post("/api/auth/otp/verify")
                        .header("X-Forwarded-For", ip)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email, "code", bad))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_attempts"));
    }

    @Test
    void secondSendWithinWindowIsRateLimited() throws Exception {
        String email = uniqueEmail();
        String ip = uniqueIp(); // pin the IP so the per email+IP bucket applies

        mvc.perform(post("/api/auth/otp/send")
                        .header("X-Forwarded-For", ip)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email))))
                .andExpect(status().isOk());

        // Second send within 30s → 429 rate_limited.
        mvc.perform(post("/api/auth/otp/send")
                        .header("X-Forwarded-For", ip)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
    }

    @Test
    void registerTokenlessStillWorksAndEmailVerifiedFalse() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("businessName", "Tokenless Biz");
        body.put("email", uniqueEmail());
        body.put("password", "correct-horse-battery");
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.emailVerified").value(false));
    }

    private String sendAndReadDevCode(String email) throws Exception {
        String bodyJson = mvc.perform(post("/api/auth/otp/send")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.readValue(bodyJson, Map.class);
        assertEquals(Boolean.TRUE, map.get("sent"));
        String code = (String) map.get("devCode");
        assertNotNull(code, "devCode present in dev-mode");
        assertTrue(code.matches("\\d{6}"), "6-digit numeric code");
        return code;
    }

    /** A 6-digit code guaranteed to differ from the given one. */
    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }
}

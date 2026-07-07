package com.mayoclone.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Register gating when {@code mayoclone.auth.require-email-otp=true} (its own
 * cached context via the property override). A valid emailVerificationToken (the
 * JWT from /otp/verify) is then REQUIRED; missing/invalid ones are rejected 400.
 */
@SpringBootTest(properties = "mayoclone.auth.require-email-otp=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegisterEmailOtpRequiredIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void registerWithoutTokenReturns400() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(reg(email(), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithInvalidTokenReturns400() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(reg(email(), "not-a-real-jwt"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithValidTokenReturns201AndEmailVerifiedTrue() throws Exception {
        String email = email();
        String token = verifiedTokenFor(email);

        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(reg(email, token))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.email").value(email))
                .andExpect(jsonPath("$.account.emailVerified").value(true));
    }

    @Test
    void registerWithTokenForDifferentEmailReturns400() throws Exception {
        String token = verifiedTokenFor(email()); // token bound to a different email
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(reg(email(), token))))
                .andExpect(status().isBadRequest());
    }

    /** Run the real send→verify flow to obtain a valid email_verify JWT. */
    private String verifiedTokenFor(String email) throws Exception {
        String sendBody = mvc.perform(post("/api/auth/otp/send")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = json.readValue(sendBody, Map.class);
        String code = (String) sent.get("devCode");
        assertNotNull(code, "devCode present in dev-mode");

        String verifyBody = mvc.perform(post("/api/auth/otp/verify")
                        .header("X-Forwarded-For", ip())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", code))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> verified = json.readValue(verifyBody, Map.class);
        String token = (String) verified.get("token");
        assertNotNull(token, "email_verify token present");
        return token;
    }

    private static Map<String, String> reg(String email, String token) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("businessName", "Acme Rail Foods");
        m.put("email", email);
        m.put("password", PASSWORD);
        if (token != null) {
            m.put("emailVerificationToken", token);
        }
        return m;
    }

    private static String email() {
        return "otpuser" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "@test.local";
    }

    private static String ip() {
        int n = SEQ.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }
}

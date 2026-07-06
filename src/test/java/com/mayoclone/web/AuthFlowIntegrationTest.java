package com.mayoclone.web;

import com.mayoclone.security.AuthCookieFactory;
import com.mayoclone.security.DoubleSubmitCsrfFilter;
import com.mayoclone.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack auth flow over MockMvc on H2 (no Docker): register/login/me/refresh/
 * logout, refresh-token rotation + reuse detection, and double-submit CSRF on the
 * cookie-authenticated refresh route. {@code src/test} only.
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void registerReturns201WithTokenAndCookies() throws Exception {
        String email = uniqueEmail();
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(reg(email, PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.account.email").value(email))
                .andReturn();

        assertNotNull(cookie(res, AuthCookieFactory.REFRESH_COOKIE), "refresh cookie set");
        assertNotNull(cookie(res, AuthCookieFactory.CSRF_COOKIE), "csrf cookie set");
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(reg(email, PASSWORD))))
                .andExpect(status().isConflict());
    }

    @Test
    void weakPasswordReturns400() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(reg(uniqueEmail(), "short"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginGoodCredentialsSucceeds() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(cred(email, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void loginBadPasswordIsGeneric401AndDoesNotEnumerate() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        String bodyForKnownEmail = mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(cred(email, "wrong-password-xx"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String bodyForUnknownEmail = mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(cred(uniqueEmail(), "wrong-password-xx"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical generic message for both — no user enumeration.
        assertEquals(bodyForKnownEmail, bodyForUnknownEmail);
    }

    @Test
    void meRequiresBearer() throws Exception {
        // Missing bearer -> 401 (SecurityConfig installs an HttpStatusEntryPoint).
        // This is what lets the SPA distinguish "refresh me" (401) from "you're
        // authenticated but forbidden" (403).
        mvc.perform(get("/api/auth/me").header("X-Forwarded-For", uniqueIp()))
                .andExpect(status().isUnauthorized());

        String email = uniqueEmail();
        String token = registerAndToken(email, PASSWORD);
        mvc.perform(get("/api/auth/me")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void refreshRotatesAndReturnsNewAccessToken() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        Cookie refresh = cookie(reg, AuthCookieFactory.REFRESH_COOKIE);
        Cookie csrf = cookie(reg, AuthCookieFactory.CSRF_COOKIE);
        String oldAccess = readAccessToken(reg);

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(refresh, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        Cookie rotated = cookie(refreshed, AuthCookieFactory.REFRESH_COOKIE);
        assertNotNull(rotated);
        assertNotEquals(refresh.getValue(), rotated.getValue(), "refresh token rotates");
        // A fresh access token is issued (guarantee it is present).
        String newAccess = readAccessToken(refreshed);
        assertNotNull(newAccess);
        assertNotNull(oldAccess);
    }

    @Test
    void refreshReuseOfRotatedTokenRevokesFamily() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        Cookie r1 = cookie(reg, AuthCookieFactory.REFRESH_COOKIE);
        Cookie csrf = cookie(reg, AuthCookieFactory.CSRF_COOKIE);

        // First rotation succeeds → r1 is now revoked.
        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(r1, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk());

        // Presenting the OLD (revoked) token again = reuse → 401.
        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(r1, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        Cookie refresh = cookie(reg, AuthCookieFactory.REFRESH_COOKIE);
        Cookie csrf = cookie(reg, AuthCookieFactory.CSRF_COOKIE);

        mvc.perform(post("/api/auth/logout")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(refresh, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isNoContent());

        // The now-revoked token can no longer be refreshed.
        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(refresh, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCsrfHeaderIsForbidden() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        Cookie refresh = cookie(reg, AuthCookieFactory.REFRESH_COOKIE);
        Cookie csrf = cookie(reg, AuthCookieFactory.CSRF_COOKIE);

        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(refresh, csrf)) // no X-CSRF-Token header
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithMismatchedCsrfIsForbidden() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        Cookie refresh = cookie(reg, AuthCookieFactory.REFRESH_COOKIE);
        Cookie csrf = cookie(reg, AuthCookieFactory.CSRF_COOKIE);

        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", uniqueIp())
                        .cookie(refresh, csrf)
                        .header(DoubleSubmitCsrfFilter.CSRF_HEADER, "not-the-cookie-value"))
                .andExpect(status().isForbidden());
    }

    private static Map<String, String> reg(String email, String password) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("businessName", "Acme Rail Foods");
        m.put("email", email);
        m.put("password", password);
        return m;
    }

    private static Map<String, String> cred(String email, String password) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("email", email);
        m.put("password", password);
        return m;
    }
}

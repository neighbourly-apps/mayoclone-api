package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The per-IP auth limiter is 10 requests/min (Bucket4j). Hammering
 * {@code POST /api/auth/login} from ONE pinned IP past that cap returns 429 with a
 * {@code Retry-After} header. Deterministic and fast: no sleeps — the 11th call
 * trips the bucket, and every request uses the fast unknown-email path.
 */
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Test
    void loginBurstFromOneIpEventuallyReturns429WithRetryAfter() throws Exception {
        String ip = "198.51.100.7"; // pinned, unique to this test
        String body = writeJson(cred("nobody-" + System.nanoTime() + "@test.local", "whatever-123"));

        MvcResult limited = null;
        int allowedCount = 0;
        // The auth bucket capacity is 10; the 11th within the window must trip it.
        for (int i = 0; i < 12; i++) {
            MvcResult res = mvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", ip)
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            int status = res.getResponse().getStatus();
            if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                limited = res;
                break;
            }
            // Before the limit, credentials are bad → 401 (never a 429 yet).
            assertEquals(HttpStatus.UNAUTHORIZED.value(), status);
            allowedCount++;
        }

        assertNotNull(limited, "expected a 429 within the burst");
        assertTrue(allowedCount <= 10, "at most 10 requests should pass before limiting");
        String retryAfter = limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertNotNull(retryAfter, "429 must carry a Retry-After header");
        assertTrue(Long.parseLong(retryAfter) >= 1, "Retry-After is a positive number of seconds");
    }

    private static Map<String, String> cred(String email, String password) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("email", email);
        m.put("password", password);
        return m;
    }
}

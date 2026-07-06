package com.mayoclone.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared base for the H2-backed MockMvc integration suites. All subclasses use the
 * SAME annotations so Spring reuses ONE cached application context (fast).
 *
 * <p>Every test method rolls back ({@link Transactional}), so writes made through
 * the controllers/services are undone between tests — even though the whole JVM
 * shares a single in-memory H2 database. The startup seeders (demo ADMIN account +
 * IRCTC aggregators) are committed once at boot and are therefore visible to all
 * tests.
 *
 * <p>Rate limiting is per-IP and lives in a JVM-wide filter bean, so helpers here
 * mint a UNIQUE client IP per call ({@code X-Forwarded-For}) to keep the per-IP
 * buckets isolated between unrelated tests. Tests that specifically exercise the
 * limiter pin their own fixed IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class AbstractIntegrationTest {

    protected static final String DEMO_ADMIN_EMAIL = "demo@mayoclone.local";
    protected static final String DEMO_ADMIN_PASSWORD = "demo-password-1";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected String uniqueEmail() {
        return "user" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "@test.local";
    }

    /** Deterministic-but-unique per call, so per-IP rate buckets never collide. */
    protected String uniqueIp() {
        int n = SEQ.incrementAndGet();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    protected String writeJson(Map<String, ?> body) throws Exception {
        return json.writeValueAsString(body);
    }

    /** POST /api/auth/register from a fresh IP; returns the raw result for assertions. */
    protected MvcResult register(String email, String password) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("businessName", "Biz " + email);
        body.put("email", email);
        body.put("password", password);
        return mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andReturn();
    }

    /** Register a fresh OWNER account and return its bearer access token. */
    protected String registerAndToken(String email, String password) throws Exception {
        MvcResult res = register(email, password);
        return readAccessToken(res);
    }

    /** POST /api/auth/login from a fresh IP. */
    protected MvcResult login(String email, String password) throws Exception {
        return login(email, password, uniqueIp());
    }

    protected MvcResult login(String email, String password, String ip) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", ip)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andReturn();
    }

    /** The seeded ADMIN (demo) account's bearer token. */
    protected String adminToken() throws Exception {
        return readAccessToken(login(DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD));
    }

    @SuppressWarnings("unchecked")
    protected String readAccessToken(MvcResult res) throws Exception {
        Map<String, Object> map = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return (String) map.get("accessToken");
    }

    protected Cookie cookie(MvcResult res, String name) {
        return res.getResponse().getCookie(name);
    }

    /** Authenticated GET helper. */
    protected MvcResult authGet(String uri, String token) throws Exception {
        return mvc.perform(get(uri)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
    }
}

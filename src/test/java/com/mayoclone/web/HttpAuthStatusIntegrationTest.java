package com.mayoclone.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-HTTP (random port) auth-status matrix. Unlike MockMvc, a live servlet
 * container performs the internal forward to {@code /error} after a denial, which
 * is exactly where the "authenticated-but-forbidden returns 401 instead of 403"
 * regression lived (the ERROR re-dispatch ran with a cleared, anonymous context
 * and hit the 401 entry point). This test locks the correct semantics:
 *
 * <ul>
 *   <li>missing/invalid auth -> 401 (so the SPA refreshes the access token),</li>
 *   <li>authenticated but not ADMIN -> 403 (so the SPA shows "forbidden", not logout).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpAuthStatusIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void anonymousProtectedRequestsReturn401() {
        assertThat(rest.getForEntity(url("/api/auth/me"), String.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(rest.getForEntity(url("/api/aggregators"), String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void authenticatedNonAdminWriteReturns403Not401() {
        String token = registerAndToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"OWNTRY\",\"name\":\"Owner Try\","
                + "\"senderDomains\":[\"owntry.test\"],\"brandColor\":\"#000000\"}";

        ResponseEntity<String> resp = rest.exchange(
                url("/api/aggregators"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        // The whole point of the regression: authenticated-but-forbidden is 403, not 401.
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void authenticatedNonAdminCanReadAggregators() {
        String token = registerAndToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/aggregators"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    private String registerAndToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String email = "own-" + UUID.randomUUID() + "@status.local";
        String body = "{\"businessName\":\"Status Co\",\"email\":\"" + email
                + "\",\"password\":\"supersecret123\"}";
        ResponseEntity<JsonNode> resp = rest.exchange(
                url("/api/auth/register"), HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        return resp.getBody().get("accessToken").asText();
    }
}

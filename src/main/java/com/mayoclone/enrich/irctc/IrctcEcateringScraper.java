package com.mayoclone.enrich.irctc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.Vendor;
import com.mayoclone.domain.VendorDashboardCredential;
import com.mayoclone.enrich.DashboardScraper;
import com.mayoclone.enrich.EnrichResult;
import com.mayoclone.enrich.EnrichmentProperties;
import com.mayoclone.enrich.OrderEnrichment;
import com.mayoclone.util.TimeoutRestClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DashboardScraper} for the real IRCTC eCatering partner API
 * ({@code https://www.ecatering.irctc.co.in/api/v1}). Pure HTTP (a timeout-bounded
 * {@link RestClient}); NO browser, NO reCAPTCHA (not enforced on the login endpoint).
 *
 * <p><b>Verified endpoints.</b>
 * <ol>
 *   <li><b>Login</b> — {@code POST {base}/auth/user/login?token=}, JSON body
 *       {@code {"mobile":"<username>","password":"<password>"}}. HTTP 200 ⇒ the response
 *       carries {@code Set-Cookie: X-AUTH=<token>; Max-Age=1209540; ...}. We capture the
 *       {@code X-AUTH} value — that IS the session — and store it on the credential with a
 *       ~13-day expiry (a little under the ~14-day cookie Max-Age). HTTP 401 ⇒ wrong
 *       credentials → {@link EnrichResult#sessionExpired()} (the vendor must re-enter).</li>
 *   <li><b>Order detail</b> — {@code GET {base}/order/{orderId}} with header
 *       {@code Cookie: X-AUTH=<stored value>}. HTTP 200 ⇒ JSON {@code {status, result:{...}}};
 *       we map {@code result} → {@link OrderEnrichment}. HTTP 401 / an auth-failure body ⇒
 *       the session died → {@link EnrichResult#sessionExpired()}. HTTP 429 ⇒
 *       {@link EnrichResult#rateLimited()}.</li>
 * </ol>
 *
 * <p><b>Cookie handling is MANUAL:</b> Spring's {@link RestClient} keeps no cookie jar, so
 * we read {@code Set-Cookie} off the login {@link ResponseEntity} ourselves, pull out the
 * {@code X-AUTH} value, and echo it back as a {@code Cookie} request header on the order
 * GET. The {@code ?token=} query param (reCAPTCHA) stays empty.
 *
 * <p><b>Session reuse:</b> {@link #fetch} tries the order GET directly when the credential
 * already holds a non-expired session; only on a 401 (or no session) does it log in and
 * retry the GET exactly once. All I/O is wrapped → typed outcomes; never returns null and
 * never throws for an auth rejection. The password and full cookie value are never logged.
 */
@Component
public class IrctcEcateringScraper implements DashboardScraper {

    public static final String PROVIDER = "IRCTC_ECATERING";

    private static final Logger log = LoggerFactory.getLogger(IrctcEcateringScraper.class);

    /** Name of the IRCTC session cookie captured at login. */
    private static final String AUTH_COOKIE = "X-AUTH";
    /** Placeholder replaced with the external order id in the order path template. */
    private static final String ORDER_ID_TOKEN = "{orderId}";
    /**
     * How long we trust a captured session before forcing a re-login. A little under the
     * portal's ~14-day (1209540s) cookie Max-Age so we never present a just-expired cookie.
     */
    private static final Duration SESSION_TTL = Duration.ofDays(13);

    private final EnrichmentProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = TimeoutRestClients.create();

    public IrctcEcateringScraper(EnrichmentProperties props) {
        this.props = props;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public EnrichResult fetch(Vendor vendor, VendorDashboardCredential cred, String externalOrderId) {
        if (cred == null || isBlank(externalOrderId)) {
            return EnrichResult.engineError();
        }
        // No stored login → nothing we can do; surface as an engine error (not a session issue).
        if (isBlank(cred.getUsername()) || isBlank(cred.getPassword())) {
            return EnrichResult.engineError();
        }

        String orderId = externalOrderId.trim();
        try {
            // 1. Reuse a live session if we have one — try the order GET straight away.
            if (hasValidSession(cred)) {
                EnrichResult direct = getOrder(cred.getSessionToken(), orderId);
                if (direct.outcome() != com.mayoclone.enrich.EnrichOutcome.SESSION_EXPIRED) {
                    return direct; // FOUND / NOT_FOUND / RATE_LIMITED / ENGINE_ERROR are final
                }
                // else: session died mid-flight → fall through to a fresh login + one retry
            }

            // 2. (Re)login with the stored mobile+password, capture the new X-AUTH.
            EnrichResult loginFailure = login(cred);
            if (loginFailure != null) {
                return loginFailure; // login rejected → typed outcome (never proceed session-less)
            }

            // 3. Retry the order GET exactly once with the freshly captured cookie.
            return getOrder(cred.getSessionToken(), orderId);
        } catch (RuntimeException e) {
            log.debug("IRCTC enrichment engine error for order {}: {}", orderId, e.toString());
            return EnrichResult.engineError();
        }
    }

    // ------------------------------------------------------------------- login

    /**
     * Log in with {@code cred}'s mobile+password and cache the captured {@code X-AUTH}
     * cookie value on {@code cred} (the job handler persists it). Returns {@code null} on
     * success, or a typed {@link EnrichResult} on failure.
     */
    private EnrichResult login(VendorDashboardCredential cred) {
        String loginUrl = props.getIrctcBaseUrl() + props.getIrctcLoginPath() + "?token=";

        Map<String, String> body = new LinkedHashMap<>();
        body.put("mobile", cred.getUsername());
        body.put("password", cred.getPassword());

        ResponseEntity<String> resp;
        try {
            resp = http.post()
                    .uri(loginUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            // 401 = "username or password not correct." → user must re-enter credentials.
            return mapHttpError(e.getStatusCode());
        }

        String token = extractAuthCookie(resp.getHeaders());
        if (isBlank(token)) {
            // 200 but no X-AUTH cookie → cannot form a session; treat as an auth failure.
            log.debug("IRCTC login returned no {} cookie", AUTH_COOKIE);
            return EnrichResult.sessionExpired();
        }
        cred.setSessionToken(token);
        cred.setSessionExpiresAt(Instant.now().plus(SESSION_TTL));
        return null;
    }

    /** Pull the {@code X-AUTH} value out of the login response's {@code Set-Cookie} headers. */
    private static String extractAuthCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        String prefix = AUTH_COOKIE + "=";
        for (String cookie : setCookies) {
            if (cookie == null) {
                continue;
            }
            String trimmed = cookie.trim();
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String value = trimmed.substring(prefix.length());
                int semi = value.indexOf(';');
                if (semi >= 0) {
                    value = value.substring(0, semi);
                }
                return blankToNull(value.trim());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- order

    /**
     * GET the order detail with the stored {@code X-AUTH} session cookie and map it.
     * A 401 / auth-failure body ⇒ {@link EnrichResult#sessionExpired()}; 429 ⇒
     * {@link EnrichResult#rateLimited()}; anything else ⇒ {@link EnrichResult#engineError()}.
     */
    private EnrichResult getOrder(String sessionToken, String orderId) {
        String url = props.getIrctcBaseUrl()
                + props.getIrctcOrderPath().replace(ORDER_ID_TOKEN, urlEncode(orderId));
        String body;
        try {
            body = http.get()
                    .uri(url)
                    .header(HttpHeaders.COOKIE, AUTH_COOKIE + "=" + safe(sessionToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            return mapHttpError(e.getStatusCode());
        }

        JsonNode root = readTree(body);
        if (isAuthFailure(root)) {
            // 200 wrapper but the portal is telling us to log in again.
            return EnrichResult.sessionExpired();
        }
        OrderEnrichment enrichment = parseOrder(root);
        if (enrichment == null || isBlank(enrichment.passengerName())) {
            return EnrichResult.notFound();
        }
        return EnrichResult.found(enrichment);
    }

    // ----------------------------------------------------------------- mapping

    /**
     * Map an IRCTC order-detail response ({@code {status, result:{customer:{...}, coach,
     * berth, pnr, ...}}}) into an {@link OrderEnrichment}. Package-visible + static so the
     * mapping is unit-testable without any HTTP. Returns {@code null} when there is no
     * usable {@code result} object or no customer name (the primary target).
     */
    static OrderEnrichment parseOrder(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            return null;
        }
        JsonNode customer = result.path("customer");
        String name = text(customer.path("fullName"));
        if (isBlank(name)) {
            return null; // no name → nothing worth returning (emails already lack it)
        }
        String phone = text(customer.path("mobile"));
        String coach = text(result.path("coach"));
        String berth = text(result.path("berth")); // may be numeric in the JSON → stringify
        String pnr = text(result.path("pnr"));
        String trainNumber = text(result.path("trainNo"));
        String trainName = text(result.path("trainName")); // emails omit the name; dashboard has it
        return new OrderEnrichment(name, phone, coach, berth, pnr, null, trainNumber, trainName);
    }

    /** True when a 200 response body is really an auth/session rejection wrapper. */
    static boolean isAuthFailure(JsonNode root) {
        if (root == null) {
            return false;
        }
        String status = text(root.path("status"));
        if (status == null || !"failure".equalsIgnoreCase(status)) {
            return false;
        }
        String message = text(root.path("message"));
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("login") || m.contains("session") || m.contains("auth")
                || m.contains("unauthor") || m.contains("token");
    }

    // ----------------------------------------------------------------- helpers

    private boolean hasValidSession(VendorDashboardCredential cred) {
        return !isBlank(cred.getSessionToken())
                && cred.getSessionExpiresAt() != null
                && cred.getSessionExpiresAt().isAfter(Instant.now());
    }

    private JsonNode readTree(String jsonBody) {
        if (isBlank(jsonBody)) {
            return null;
        }
        try {
            return mapper.readTree(jsonBody);
        } catch (Exception e) {
            return null;
        }
    }

    private static EnrichResult mapHttpError(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403 || (code >= 300 && code < 400)) {
            // Unauthorized / forbidden / redirect to the login page ⇒ our session died.
            return EnrichResult.sessionExpired();
        }
        if (code == 429) {
            return EnrichResult.rateLimited();
        }
        return EnrichResult.engineError();
    }

    /** Read a JSON node as trimmed text (works for strings and numbers); null when absent. */
    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return blankToNull(node.asText(null));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

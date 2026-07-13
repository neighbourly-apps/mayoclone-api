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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link DashboardScraper} for the IRCTC eCatering partner portal. Pure HTTP (a
 * timeout-bounded {@link RestClient}); NO browser.
 *
 * <p>Flow — fully CONFIGURABLE via {@link EnrichmentProperties} so the real endpoints
 * and JSON shape can be filled in LATER without a code change:
 * <ol>
 *   <li>If the credential has no valid cached session → POST {@code {username,password}}
 *       to {@code login-url}; capture the returned bearer token onto {@code cred}
 *       (encrypted at rest) with a session expiry.</li>
 *   <li>GET {@code order-url} with {@code {orderId}} substituted, sending the session as
 *       a Bearer token; parse the JSON and read the name at {@code name-json-path}
 *       (default {@code customer.fullName}).</li>
 * </ol>
 *
 * <p>When {@code login-url}/{@code order-url} are BLANK (the shipped default) the scraper
 * is idle: it returns {@code ENGINE_ERROR}/{@code NOT_FOUND} cleanly and never touches
 * the network. All HTTP is wrapped → typed outcomes: 401/redirect-to-login ⇒
 * SESSION_EXPIRED, 429 ⇒ RATE_LIMITED, anything else ⇒ ENGINE_ERROR. Never returns null.
 */
@Component
public class IrctcEcateringScraper implements DashboardScraper {

    public static final String PROVIDER = "IRCTC_ECATERING";

    private static final Logger log = LoggerFactory.getLogger(IrctcEcateringScraper.class);

    /** Default dotted path to the passenger/customer name in the lookup JSON. */
    private static final String DEFAULT_NAME_PATH = "customer.fullName";
    /** Placeholder replaced with the external order id in {@code order-url}. */
    private static final String ORDER_ID_TOKEN = "{orderId}";
    /** How long a captured session is trusted before we re-login. */
    private static final long SESSION_TTL_MS = 20 * 60 * 1000L; // 20 minutes

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
        try {
            // 1. Ensure a session (log in with the stored username/password when needed).
            if (!hasValidSession(cred)) {
                EnrichResult login = login(cred);
                if (login != null) {
                    return login; // login failed → typed outcome (never proceed without a session)
                }
            }

            // 2. Look the order up.
            String orderUrl = props.getIrctcOrderUrl();
            if (isBlank(orderUrl)) {
                // Endpoint not configured yet → feature idle, nothing to add.
                return EnrichResult.notFound();
            }
            String url = orderUrl.replace(ORDER_ID_TOKEN, urlEncode(externalOrderId.trim()));
            String body = http.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + safe(cred.getSessionToken()))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            OrderEnrichment enrichment = parseEnrichment(body);
            if (enrichment == null || isBlank(enrichment.passengerName())) {
                return EnrichResult.notFound();
            }
            return EnrichResult.found(enrichment);
        } catch (RestClientResponseException hre) {
            return mapHttpError(hre.getStatusCode());
        } catch (RuntimeException e) {
            log.debug("IRCTC enrichment engine error for order {}: {}", externalOrderId, e.toString());
            return EnrichResult.engineError();
        }
    }

    /**
     * Log in and cache the session on {@code cred}. Returns {@code null} on success
     * (the caller proceeds to the lookup), or a typed {@link EnrichResult} on failure.
     */
    private EnrichResult login(VendorDashboardCredential cred) {
        String loginUrl = props.getIrctcLoginUrl();
        if (isBlank(loginUrl)) {
            // Not configured yet → cannot authenticate; feature idle.
            return EnrichResult.engineError();
        }
        if (isBlank(cred.getUsername()) || isBlank(cred.getPassword())) {
            return EnrichResult.sessionExpired();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", cred.getUsername());
        form.put("password", cred.getPassword());

        String body;
        try {
            body = http.post()
                    .uri(loginUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            return mapHttpError(e.getStatusCode());
        }

        String token = extractToken(body);
        if (isBlank(token)) {
            // Portal accepted the request but gave us no usable session → treat as auth failure.
            return EnrichResult.sessionExpired();
        }
        cred.setSessionToken(token);
        cred.setSessionExpiresAt(Instant.now().plusMillis(SESSION_TTL_MS));
        return null;
    }

    private boolean hasValidSession(VendorDashboardCredential cred) {
        return !isBlank(cred.getSessionToken())
                && cred.getSessionExpiresAt() != null
                && cred.getSessionExpiresAt().isAfter(Instant.now());
    }

    /** Pull the bearer token out of the login response ("token" or "accessToken"). */
    private String extractToken(String jsonBody) {
        JsonNode root = readTree(jsonBody);
        if (root == null) {
            return null;
        }
        String token = readPath(root, "token");
        if (isBlank(token)) {
            token = readPath(root, "accessToken");
        }
        if (isBlank(token)) {
            token = readPath(root, "data.token");
        }
        return token;
    }

    private OrderEnrichment parseEnrichment(String jsonBody) {
        JsonNode root = readTree(jsonBody);
        if (root == null) {
            return null;
        }
        String namePath = isBlank(props.getIrctcNameJsonPath())
                ? DEFAULT_NAME_PATH
                : props.getIrctcNameJsonPath();
        String name = readPath(root, namePath);
        // Phone is best-effort; the primary target is the name.
        String phone = readPath(root, "customer.mobile");
        if (isBlank(name)) {
            return null;
        }
        return new OrderEnrichment(name, blankToNull(phone), null, null, null, null);
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

    /** Walk a dotted JSON path (e.g. {@code customer.fullName}); returns text or null. */
    private static String readPath(JsonNode root, String dottedPath) {
        JsonNode node = root;
        for (String segment : dottedPath.split("\\.")) {
            if (node == null || node.isMissingNode()) {
                return null;
            }
            node = node.path(segment);
        }
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String text = node.asText(null);
        return blankToNull(text);
    }

    private static EnrichResult mapHttpError(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403 || (code >= 300 && code < 400)) {
            // Unauthorized / forbidden / a redirect to the login page ⇒ our session died.
            return EnrichResult.sessionExpired();
        }
        if (code == 429) {
            return EnrichResult.rateLimited();
        }
        return EnrichResult.engineError();
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

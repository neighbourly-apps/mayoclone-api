package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Drives the Gmail OAuth "connect a mailbox" flow and token lifecycle. All heavy
 * lifting uses Spring's {@link RestClient} (no google-api client libs). Live
 * Google calls are never exercised by the build; unit tests cover URL/query
 * building and state signing only.
 *
 * <p>When Gmail is not configured ({@link GmailOAuthConfig#isEnabled()} == false)
 * the public entry points throw 503 so callers get a clean "not configured".
 */
@Service
public class GmailOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuthService.class);

    private final GmailOAuthConfig config;
    private final OAuthStateSigner stateSigner;
    private final VendorRepository vendorRepo;
    private final AggregatorRepository aggregatorRepo;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    // Timeouts (connect 3s / read 10s) so a wedged Google socket cannot hang the
    // OAuth callback / token-refresh thread forever. Bare create() has NO timeouts.
    private final RestClient restClient = com.mayoclone.util.TimeoutRestClients.create();

    public GmailOAuthService(GmailOAuthConfig config,
                             OAuthStateSigner stateSigner,
                             VendorRepository vendorRepo,
                             AggregatorRepository aggregatorRepo,
                             AuditService auditService,
                             ApplicationEventPublisher events) {
        this.config = config;
        this.stateSigner = stateSigner;
        this.vendorRepo = vendorRepo;
        this.aggregatorRepo = aggregatorRepo;
        this.auditService = auditService;
        this.events = events;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /** Build the Google consent URL (with a signed state) for the given account. */
    public String buildConsentUrl(long accountId) {
        requireEnabled();
        String state = stateSigner.sign(accountId);
        return buildAuthorizationUrl(config.clientId(), config.redirectUri(), state);
    }

    /**
     * Pure URL builder (package-visible for tests). Includes offline access +
     * forced consent so a refresh token is always returned, and the readonly scope.
     */
    static String buildAuthorizationUrl(String clientId, String redirectUri, String state) {
        return GmailOAuthConfig.AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(GmailOAuthConfig.SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state=" + enc(state);
    }

    /**
     * Handle the OAuth callback: validate the signed state to recover the account,
     * exchange the code for tokens, and persist the encrypted refresh token on a
     * new/updated GMAIL_OAUTH vendor. Returns the SPA URL to redirect back to.
     */
    public String handleCallback(String code, String state) {
        requireEnabled();
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing authorization code");
        }
        long accountId;
        try {
            accountId = stateSigner.verify(state).accountId();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid OAuth state: " + e.getMessage());
        }

        Map<String, Object> tokens = exchangeCode(code);
        String refreshToken = str(tokens.get("refresh_token"));
        String accessToken = str(tokens.get("access_token"));
        if (refreshToken == null) {
            // Google omits the refresh token if the user previously consented and we
            // didn't force prompt=consent — we do force it, so this is a real error.
            throw new ResponseStatusException(BAD_GATEWAY, "Google did not return a refresh token");
        }
        String email = fetchProfileEmail(accessToken);

        Vendor vendor = vendorRepo
                .findByAccountIdAndSourceTypeAndOauthEmail(accountId, MailSourceType.GMAIL_OAUTH, email)
                .orElseGet(Vendor::new);
        if (vendor.getId() == null) {
            // One mailbox per account: reconnecting the SAME Gmail updates the existing
            // vendor above; connecting a NEW mailbox when one already exists is blocked.
            if (vendorRepo.countByAccountId(accountId) > 0) {
                throw new ResponseStatusException(CONFLICT,
                        "A mailbox is already connected to this account. Remove it before adding another.");
            }
            vendor.setAccountId(accountId);
            vendor.setRestaurantName(email != null ? "Gmail: " + email : "Gmail mailbox");
            vendor.setOwnerEmail(email);
            vendor.setStationCode("");
            vendor.setCreatedAt(Instant.now());
        }
        vendor.setSourceType(MailSourceType.GMAIL_OAUTH);
        vendor.setOauthEmail(email);
        vendor.setOauthRefreshToken(refreshToken); // encrypted at rest by the converter
        vendor.setOauthConnectedAt(Instant.now());
        vendor.setActive(true);
        vendorRepo.save(vendor);

        auditService.record(accountId, email, "gmail.connect", "vendor",
                String.valueOf(vendor.getId()), Map.of("email", email == null ? "" : email));
        log.info("Connected Gmail mailbox for account {}", accountId);

        // Best-effort: kick off a users.watch registration (decoupled to avoid a
        // dependency cycle). When no Pub/Sub topic is configured the listener no-ops.
        events.publishEvent(new GmailConnectedEvent(vendor.getId()));

        return config.frontendBaseUrl() + "/vendors?gmail=connected";
    }

    /** Exchange an authorization code for an access + refresh token pair. */
    Map<String, Object> exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("redirect_uri", config.redirectUri());
        form.add("grant_type", "authorization_code");
        return postForm(form);
    }

    /** Redeem the vendor's refresh token for a short-lived access token. */
    public String refreshAccessToken(String refreshToken) {
        requireEnabled();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("grant_type", "refresh_token");
        return str(postForm(form).get("access_token"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(GmailOAuthConfig.TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(BAD_GATEWAY, "Google token endpoint error");
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchProfileEmail(String accessToken) {
        if (accessToken == null) {
            return null;
        }
        try {
            Map<String, Object> profile = restClient.get()
                    .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/profile")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            return profile == null ? null : str(profile.get("emailAddress"));
        } catch (RuntimeException e) {
            log.warn("Could not fetch Gmail profile email: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a Gmail search query from the active aggregators' sender domains, e.g.
     * {@code from:(zoopindia.com OR railrestro.com)}. Package-visible for tests.
     */
    public String buildGmailQuery() {
        // Sorted + de-duplicated for a stable, testable query.
        TreeSet<String> domains = aggregatorRepo.findByActiveTrue().stream()
                .flatMap(a -> a.getSenderDomains().stream())
                .filter(d -> d != null && !d.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(TreeSet::new));
        if (domains.isEmpty()) {
            return "";
        }
        return "from:(" + String.join(" OR ", domains) + ")";
    }

    List<String> activeSenderDomains() {
        return aggregatorRepo.findByActiveTrue().stream()
                .flatMap(a -> a.getSenderDomains().stream())
                .filter(d -> d != null && !d.isBlank())
                .toList();
    }

    RestClient restClient() {
        return restClient;
    }

    GmailOAuthConfig config() {
        return config;
    }

    private void requireEnabled() {
        if (!config.isEnabled()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Gmail OAuth not configured");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

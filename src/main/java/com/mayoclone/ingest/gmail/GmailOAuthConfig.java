package com.mayoclone.ingest.gmail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google OAuth configuration. When any of the client id / secret / redirect URI
 * is absent the Gmail feature is DISABLED gracefully: the app still starts and
 * the Gmail endpoints return 503. No Google credentials are required to build,
 * run tests, or boot locally.
 */
@Component
public class GmailOAuthConfig {

    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    public static final String GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String frontendBaseUrl;

    public GmailOAuthConfig(
            @Value("${google.oauth.client-id:}") String clientId,
            @Value("${google.oauth.client-secret:}") String clientSecret,
            @Value("${google.oauth.redirect-uri:}") String redirectUri,
            @Value("${mayoclone.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** True only when all three Google credentials are present. */
    public boolean isEnabled() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String frontendBaseUrl() {
        return frontendBaseUrl;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

package com.mayoclone.ingest.gmail;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates an incoming Google Pub/Sub push request in one of three modes,
 * selected by {@link GmailPushProperties}:
 *
 * <ul>
 *   <li><b>OIDC</b> (audience configured) — require an {@code Authorization: Bearer}
 *       Google-signed OIDC token whose {@code aud} matches (via
 *       {@link GoogleOidcTokenVerifier}). This is the production-grade mode.</li>
 *   <li><b>Shared token</b> (only token configured) — require a matching {@code ?token=}
 *       query param, compared in constant time.</li>
 *   <li><b>Disabled</b> (neither configured) — the endpoint is off.</li>
 * </ul>
 */
@Component
public class GmailPushVerifier {

    /** Outcome of verifying a push request. */
    public enum Result {
        /** Verified — process the push. */
        OK,
        /** Auth present but invalid/missing → 401. */
        UNAUTHORIZED,
        /** No verification configured → endpoint disabled → 503. */
        DISABLED
    }

    private final GmailPushProperties props;
    private final GoogleOidcTokenVerifier oidc;

    public GmailPushVerifier(GmailPushProperties props, GoogleOidcTokenVerifier oidc) {
        this.props = props;
        this.oidc = oidc;
    }

    public Result verify(HttpServletRequest request) {
        if (props.audienceConfigured()) {
            String bearer = bearerToken(request);
            return oidc.verify(bearer, props.pushAudience()) ? Result.OK : Result.UNAUTHORIZED;
        }
        if (props.tokenConfigured()) {
            String provided = request.getParameter("token");
            return constantTimeEquals(props.pushToken(), provided) ? Result.OK : Result.UNAUTHORIZED;
        }
        return Result.DISABLED;
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}

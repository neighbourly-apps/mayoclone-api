package com.mayoclone.ingest.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies a Google-signed OIDC ID token, as delivered on a Pub/Sub push
 * subscription's {@code Authorization: Bearer} header. Verification is done with
 * plain {@code java.security} (RS256) against a JWKS key resolved by
 * {@link JwksKeyResolver} — no extra JWT/JOSE dependency.
 *
 * <p>Checks, in order: three-segment JWT; RS256 header; a known {@code kid};
 * signature over {@code header.payload}; issuer in
 * {@code {accounts.google.com, https://accounts.google.com}}; {@code aud} equals the
 * expected audience; {@code exp} not in the past (30s skew); and — when a push service
 * account is configured — {@code email_verified == true} AND {@code email} equals that
 * account. Without the identity pin, ANYONE who knows the audience can forge a push;
 * the pin ties the token to Google's Pub/Sub push service identity.
 */
@Component
public class GoogleOidcTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcTokenVerifier.class);

    private static final Set<String> VALID_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final JwksKeyResolver keyResolver;
    /** Expected push service-account email; blank ⇒ identity pin NOT enforced (warns once). */
    private final String expectedServiceAccount;
    private final AtomicBoolean warnedUnpinned = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GoogleOidcTokenVerifier(JwksKeyResolver keyResolver,
                                   @Value("${mayoclone.gmail.push.service-account:}") String expectedServiceAccount) {
        this.keyResolver = keyResolver;
        this.expectedServiceAccount = expectedServiceAccount == null ? "" : expectedServiceAccount.trim();
    }

    /** Convenience/test constructor: no identity pin (email not enforced). */
    public GoogleOidcTokenVerifier(JwksKeyResolver keyResolver) {
        this(keyResolver, "");
    }

    /**
     * @return true iff {@code token} is a valid, unexpired, correctly-audienced
     *         Google-signed OIDC token. Never throws — any problem yields false.
     */
    public boolean verify(String token, String expectedAudience) {
        if (token == null || token.isBlank() || expectedAudience == null || expectedAudience.isBlank()) {
            return false;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            Map<String, Object> header = json(parts[0]);
            if (!"RS256".equals(str(header.get("alg")))) {
                return false;
            }
            String kid = str(header.get("kid"));
            RSAPublicKey key = keyResolver.resolve(kid);
            if (key == null) {
                log.debug("OIDC verify: no JWKS key for kid={}", kid);
                return false;
            }
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(key);
            rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                log.debug("OIDC verify: bad signature");
                return false;
            }
            Map<String, Object> claims = json(parts[1]);
            if (!VALID_ISSUERS.contains(str(claims.get("iss")))) {
                return false;
            }
            if (!audienceMatches(claims.get("aud"), expectedAudience)) {
                return false;
            }
            long exp = asLong(claims.get("exp"));
            long now = System.currentTimeMillis() / 1000L;
            if (exp <= 0 || exp + CLOCK_SKEW_SECONDS < now) {
                return false;
            }
            return identityMatches(claims);
        } catch (Exception e) {
            log.debug("OIDC verify failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Identity pin: when a service account is configured, require {@code email_verified}
     * true AND {@code email} == configured account. When blank, warn once and accept
     * (signature/iss/aud/exp already checked) so the feature degrades, not fails.
     */
    private boolean identityMatches(Map<String, Object> claims) {
        if (expectedServiceAccount.isBlank()) {
            if (warnedUnpinned.compareAndSet(false, true)) {
                log.warn("Gmail push OIDC identity NOT pinned — set mayoclone.gmail.push.service-account "
                        + "to the push service account so a forged token with the right audience is rejected");
            }
            return true;
        }
        boolean emailVerified = Boolean.TRUE.equals(claims.get("email_verified"))
                || "true".equalsIgnoreCase(str(claims.get("email_verified")));
        String email = str(claims.get("email"));
        if (!emailVerified || email == null || !expectedServiceAccount.equalsIgnoreCase(email.trim())) {
            log.debug("OIDC verify: email pin failed (email={}, verified={})", email, emailVerified);
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String b64urlSegment) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(b64urlSegment);
        return mapper.readValue(decoded, Map.class);
    }

    /** The {@code aud} claim may be a single string or an array of strings (RFC 7519). */
    private static boolean audienceMatches(Object aud, String expected) {
        if (aud instanceof java.util.Collection<?> col) {
            for (Object a : col) {
                if (expected.equals(str(a))) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(str(aud));
    }

    private static long asLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

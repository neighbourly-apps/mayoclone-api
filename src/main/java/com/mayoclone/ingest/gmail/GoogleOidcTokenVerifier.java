package com.mayoclone.ingest.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a Google-signed OIDC ID token, as delivered on a Pub/Sub push
 * subscription's {@code Authorization: Bearer} header. Verification is done with
 * plain {@code java.security} (RS256) against a JWKS key resolved by
 * {@link JwksKeyResolver} — no extra JWT/JOSE dependency.
 *
 * <p>Checks, in order: three-segment JWT; RS256 header; a known {@code kid};
 * signature over {@code header.payload}; issuer in
 * {@code {accounts.google.com, https://accounts.google.com}}; {@code aud} equals the
 * expected audience; and {@code exp} not in the past (30s skew).
 */
@Component
public class GoogleOidcTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcTokenVerifier.class);

    private static final Set<String> VALID_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final JwksKeyResolver keyResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleOidcTokenVerifier(JwksKeyResolver keyResolver) {
        this.keyResolver = keyResolver;
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
            return exp > 0 && exp + CLOCK_SKEW_SECONDS >= now;
        } catch (Exception e) {
            log.debug("OIDC verify failed: {}", e.getMessage());
            return false;
        }
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

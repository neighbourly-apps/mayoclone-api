package com.mayoclone.ingest.gmail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live {@link JwksKeyResolver} that fetches and caches Google's OIDC signing keys
 * from {@code https://www.googleapis.com/oauth2/v3/certs} (RSA {@code n}/{@code e}
 * pairs → {@link RSAPublicKey}). Keys are cached and refreshed lazily: on a cache
 * miss (unknown {@code kid}) or once the cache TTL lapses. Never fetched at startup,
 * so it is inert in builds/tests where the push feature is off.
 */
@Component
public class GoogleJwksKeyResolver implements JwksKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(GoogleJwksKeyResolver.class);

    /** Google rotates keys slowly; a 1h cache with miss-triggered refresh is ample. */
    private static final long TTL_MS = 60 * 60 * 1000L;

    private final String certsUrl;
    // Timeouts (connect 3s / read 10s) so a wedged JWKS fetch cannot hang the push
    // verification thread forever. Bare RestClient.create() has NO timeouts.
    private final RestClient http = com.mayoclone.util.TimeoutRestClients.create();

    private final ConcurrentHashMap<String, RSAPublicKey> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastRefresh = new AtomicLong(0);

    public GoogleJwksKeyResolver() {
        this("https://www.googleapis.com/oauth2/v3/certs");
    }

    /** Package-visible for tests that want to point at a stub certs endpoint. */
    GoogleJwksKeyResolver(String certsUrl) {
        this.certsUrl = certsUrl;
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        if (kid == null) {
            return null;
        }
        RSAPublicKey key = cache.get(kid);
        long now = System.currentTimeMillis();
        if (key == null || now - lastRefresh.get() > TTL_MS) {
            refresh();
            key = cache.get(kid);
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh() {
        // Another thread may have refreshed while we waited on the lock.
        if (System.currentTimeMillis() - lastRefresh.get() <= TTL_MS && !cache.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = http.get().uri(certsUrl).retrieve().body(Map.class);
            List<Map<String, Object>> keys = body == null
                    ? List.of() : (List<Map<String, Object>>) body.getOrDefault("keys", List.of());
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            for (Map<String, Object> jwk : keys) {
                String kty = str(jwk.get("kty"));
                String kid = str(jwk.get("kid"));
                if (!"RSA".equals(kty) || kid == null) {
                    continue;
                }
                BigInteger n = new BigInteger(1, b64url(str(jwk.get("n"))));
                BigInteger e = new BigInteger(1, b64url(str(jwk.get("e"))));
                cache.put(kid, (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(n, e)));
            }
            lastRefresh.set(System.currentTimeMillis());
        } catch (Exception ex) {
            log.warn("Failed to refresh Google JWKS from {}: {}", certsUrl, ex.getMessage());
        }
    }

    private static byte[] b64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

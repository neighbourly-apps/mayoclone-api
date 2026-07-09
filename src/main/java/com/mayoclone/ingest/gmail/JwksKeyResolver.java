package com.mayoclone.ingest.gmail;

import java.security.interfaces.RSAPublicKey;

/**
 * Resolves an RSA public key by its JWKS {@code kid}. Extracted so the OIDC
 * verifier can be unit-tested with an in-memory key (no network to Google's JWKS
 * endpoint). {@link GoogleJwksKeyResolver} is the live, caching implementation.
 */
@FunctionalInterface
public interface JwksKeyResolver {

    /** The RSA public key for {@code kid}, or null if it is unknown after a refresh. */
    RSAPublicKey resolve(String kid);
}

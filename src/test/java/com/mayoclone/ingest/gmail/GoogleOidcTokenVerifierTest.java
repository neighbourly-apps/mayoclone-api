package com.mayoclone.ingest.gmail;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the manual RS256 / JWKS OIDC verification with an in-memory RSA key
 * (no network). Tokens are built with jjwt (already on the classpath) and checked
 * by {@link GoogleOidcTokenVerifier} through a stub {@link JwksKeyResolver}.
 */
class GoogleOidcTokenVerifierTest {

    private static final String KID = "test-kid-1";
    private static final String AUDIENCE = "https://api.mayoclone.example/api/inbound/gmail/push";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static GoogleOidcTokenVerifier verifier;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
        // Resolver returns the test key only for the matching kid.
        JwksKeyResolver resolver = kid -> KID.equals(kid) ? publicKey : null;
        verifier = new GoogleOidcTokenVerifier(resolver);
    }

    private static String token(String issuer, String audience, Instant exp, String kid) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject("pubsub@system.gserviceaccount.com")
                .expiration(Date.from(exp))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void acceptsValidGoogleSignedToken() {
        String t = token("https://accounts.google.com", AUDIENCE, Instant.now().plusSeconds(600), KID);
        assertTrue(verifier.verify(t, AUDIENCE));
    }

    @Test
    void acceptsBareAccountsGoogleComIssuer() {
        String t = token("accounts.google.com", AUDIENCE, Instant.now().plusSeconds(600), KID);
        assertTrue(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsWrongAudience() {
        String t = token("https://accounts.google.com", "some-other-audience",
                Instant.now().plusSeconds(600), KID);
        assertFalse(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsWrongIssuer() {
        String t = token("https://evil.example", AUDIENCE, Instant.now().plusSeconds(600), KID);
        assertFalse(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsExpiredToken() {
        String t = token("https://accounts.google.com", AUDIENCE, Instant.now().minusSeconds(600), KID);
        assertFalse(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsUnknownKid() {
        String t = token("https://accounts.google.com", AUDIENCE, Instant.now().plusSeconds(600), "unknown-kid");
        assertFalse(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey otherKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        String t = Jwts.builder()
                .header().keyId(KID).and()
                .issuer("https://accounts.google.com")
                .audience().add(AUDIENCE).and()
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(otherKey, Jwts.SIG.RS256)
                .compact();
        assertFalse(verifier.verify(t, AUDIENCE));
    }

    @Test
    void rejectsMalformedAndBlankTokens() {
        assertFalse(verifier.verify(null, AUDIENCE));
        assertFalse(verifier.verify("", AUDIENCE));
        assertFalse(verifier.verify("not-a-jwt", AUDIENCE));
        assertFalse(verifier.verify("a.b", AUDIENCE));
    }
}

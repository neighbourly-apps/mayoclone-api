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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gmail push OIDC identity pin: when a service account is configured, a token whose
 * audience/issuer/signature are valid is STILL rejected unless email_verified==true and
 * email == the configured account — so knowing the audience is not enough to forge a push.
 */
class GoogleOidcTokenVerifierEmailPinTest {

    private static final String KID = "test-kid-1";
    private static final String AUDIENCE = "https://api.mayoclone.example/api/inbound/gmail/push";
    private static final String SERVICE_ACCOUNT = "pubsub-push@my-proj.iam.gserviceaccount.com";

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
    }

    private static GoogleOidcTokenVerifier pinned() {
        JwksKeyResolver resolver = kid -> KID.equals(kid) ? publicKey : null;
        return new GoogleOidcTokenVerifier(resolver, SERVICE_ACCOUNT);
    }

    private static String token(Map<String, Object> extraClaims) {
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .issuer("https://accounts.google.com")
                .audience().add(AUDIENCE).and()
                .expiration(Date.from(Instant.now().plusSeconds(600)));
        extraClaims.forEach(builder::claim);
        return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    @Test
    void acceptsTokenFromConfiguredVerifiedServiceAccount() {
        String t = token(Map.of("email", SERVICE_ACCOUNT, "email_verified", true));
        assertTrue(pinned().verify(t, AUDIENCE));
    }

    @Test
    void rejectsWrongEmailEvenWithValidSignatureAndAudience() {
        String t = token(Map.of("email", "attacker@evil.example", "email_verified", true));
        assertFalse(pinned().verify(t, AUDIENCE));
    }

    @Test
    void rejectsWhenEmailNotVerified() {
        String t = token(Map.of("email", SERVICE_ACCOUNT, "email_verified", false));
        assertFalse(pinned().verify(t, AUDIENCE));
    }

    @Test
    void rejectsWhenEmailClaimMissing() {
        String t = token(Map.of("email_verified", true));
        assertFalse(pinned().verify(t, AUDIENCE));
    }

    @Test
    void unpinnedVerifierAcceptsRegardlessOfEmail() {
        // Blank service account (default) → identity not pinned; still passes on sig/aud/exp.
        JwksKeyResolver resolver = kid -> KID.equals(kid) ? publicKey : null;
        GoogleOidcTokenVerifier unpinned = new GoogleOidcTokenVerifier(resolver);
        String t = token(Map.of("email", "anyone@example.com", "email_verified", false));
        assertTrue(unpinned.verify(t, AUDIENCE));
    }
}

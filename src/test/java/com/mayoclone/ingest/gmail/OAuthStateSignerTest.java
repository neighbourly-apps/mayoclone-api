package com.mayoclone.ingest.gmail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OAuth state signing: round-trip, tamper detection, expiry, and wrong-secret. */
class OAuthStateSignerTest {

    private static final String SECRET = "oauth-state-test-secret-32-bytes-long";

    @Test
    void signsAndVerifiesRoundTrip() {
        OAuthStateSigner signer = new OAuthStateSigner(SECRET, 600);
        String state = signer.sign(4242L);
        assertEquals(4242L, signer.verify(state).accountId());
    }

    @Test
    void rejectsTamperedState() {
        OAuthStateSigner signer = new OAuthStateSigner(SECRET, 600);
        String state = signer.sign(7L);
        // Mutate the first char of the payload segment → recomputed HMAC won't match.
        char first = state.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + state.substring(1);
        assertThrows(IllegalArgumentException.class, () -> signer.verify(tampered));
    }

    @Test
    void rejectsExpiredState() {
        // Negative TTL → the state is already expired when minted.
        OAuthStateSigner signer = new OAuthStateSigner(SECRET, -10);
        String state = signer.sign(1L);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> signer.verify(state));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"));
    }

    @Test
    void rejectsStateSignedWithADifferentSecret() {
        String state = new OAuthStateSigner("secret-a", 600).sign(9L);
        OAuthStateSigner other = new OAuthStateSigner("secret-b", 600);
        assertThrows(IllegalArgumentException.class, () -> other.verify(state));
    }

    @Test
    void rejectsMalformedState() {
        OAuthStateSigner signer = new OAuthStateSigner(SECRET, 600);
        assertThrows(IllegalArgumentException.class, () -> signer.verify(null));
        assertThrows(IllegalArgumentException.class, () -> signer.verify("no-dot"));
        assertThrows(IllegalArgumentException.class, () -> signer.verify("a.b"));
    }
}

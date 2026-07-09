package com.mayoclone.ingest.gmail;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Auth-mode selection + rejection for the Gmail push endpoint. No network. */
class GmailPushVerifierTest {

    private static GmailPushProperties props(String topic, String audience, String token) {
        return new GmailPushProperties(topic, audience, token, 21600000L, true);
    }

    // ------------------------------------------------------------------ disabled

    @Test
    void neitherAudienceNorTokenConfigured_disabled() {
        GmailPushVerifier v = new GmailPushVerifier(
                props("projects/p/topics/t", "", ""), mock(GoogleOidcTokenVerifier.class));
        assertEquals(GmailPushVerifier.Result.DISABLED, v.verify(new MockHttpServletRequest()));
    }

    // ------------------------------------------------------------- token fallback

    @Test
    void tokenMode_matchingQueryParam_ok() {
        GmailPushVerifier v = new GmailPushVerifier(
                props("", "", "s3cr3t-token"), mock(GoogleOidcTokenVerifier.class));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", "s3cr3t-token");
        assertEquals(GmailPushVerifier.Result.OK, v.verify(req));
    }

    @Test
    void tokenMode_wrongOrMissingToken_unauthorized() {
        GmailPushVerifier v = new GmailPushVerifier(
                props("", "", "s3cr3t-token"), mock(GoogleOidcTokenVerifier.class));
        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.setParameter("token", "nope");
        assertEquals(GmailPushVerifier.Result.UNAUTHORIZED, v.verify(wrong));
        assertEquals(GmailPushVerifier.Result.UNAUTHORIZED, v.verify(new MockHttpServletRequest()));
    }

    // ------------------------------------------------------------------ OIDC mode

    @Test
    void oidcMode_validBearer_ok() {
        GoogleOidcTokenVerifier oidc = mock(GoogleOidcTokenVerifier.class);
        when(oidc.verify("good-jwt", "aud-123")).thenReturn(true);
        GmailPushVerifier v = new GmailPushVerifier(props("", "aud-123", ""), oidc);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer good-jwt");
        assertEquals(GmailPushVerifier.Result.OK, v.verify(req));
    }

    @Test
    void oidcMode_invalidOrMissingBearer_unauthorized() {
        GoogleOidcTokenVerifier oidc = mock(GoogleOidcTokenVerifier.class);
        when(oidc.verify("bad-jwt", "aud-123")).thenReturn(false);
        GmailPushVerifier v = new GmailPushVerifier(props("", "aud-123", ""), oidc);

        MockHttpServletRequest withBad = new MockHttpServletRequest();
        withBad.addHeader("Authorization", "Bearer bad-jwt");
        assertEquals(GmailPushVerifier.Result.UNAUTHORIZED, v.verify(withBad));

        // No Authorization header at all → oidc.verify(null, ...) → false.
        assertEquals(GmailPushVerifier.Result.UNAUTHORIZED, v.verify(new MockHttpServletRequest()));
    }

    @Test
    void audienceTakesPrecedenceOverToken() {
        GoogleOidcTokenVerifier oidc = mock(GoogleOidcTokenVerifier.class);
        when(oidc.verify("jwt", "aud-123")).thenReturn(true);
        // Both configured → OIDC path is used (token param ignored).
        GmailPushVerifier v = new GmailPushVerifier(props("", "aud-123", "also-a-token"), oidc);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer jwt");
        req.setParameter("token", "also-a-token");
        assertEquals(GmailPushVerifier.Result.OK, v.verify(req));
    }
}

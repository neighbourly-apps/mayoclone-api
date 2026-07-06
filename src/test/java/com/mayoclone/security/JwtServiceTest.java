package com.mayoclone.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-that-is-definitely-32-plus-bytes";

    private JwtService service() {
        return new JwtService(SECRET, "mayoclone-test", 900);
    }

    @Test
    void issuesAndParsesRoundTrip() {
        JwtService jwt = service();
        String token = jwt.issueAccessToken(42L, "owner@biz.test", "OWNER");
        assertNotNull(token);

        AccountPrincipal p = jwt.parse(token);
        assertNotNull(p);
        assertEquals(42L, p.id());
        assertEquals("owner@biz.test", p.email());
        assertEquals("OWNER", p.role());
    }

    @Test
    void rejectsTamperedToken() {
        JwtService jwt = service();
        String token = jwt.issueAccessToken(1L, "a@b.c", "ADMIN");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertNull(jwt.parse(tampered));
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        String token = new JwtService(SECRET, "mayoclone-test", 900)
                .issueAccessToken(1L, "a@b.c", "OWNER");
        JwtService other = new JwtService(
                "a-totally-different-secret-key-also-32-plus-bytes-long", "mayoclone-test", 900);
        assertNull(other.parse(token));
    }

    @Test
    void nullOrBlankReturnsNull() {
        JwtService jwt = service();
        assertNull(jwt.parse(null));
        assertNull(jwt.parse("   "));
    }

    @Test
    void shortSecretIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService("too-short", "mayoclone", 900));
    }
}

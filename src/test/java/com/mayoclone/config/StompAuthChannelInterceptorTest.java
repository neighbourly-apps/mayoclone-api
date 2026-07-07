package com.mayoclone.config;

import com.mayoclone.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the STOMP CONNECT interceptor rejects a missing/invalid token and, on a
 * valid token, sets the session Principal to the tenant's accountId.
 */
class StompAuthChannelInterceptorTest {

    private final JwtService jwt = new JwtService(
            "test-only-jwt-secret-value-that-is-long-enough-0123456789", "mayoclone-test", 900, 900);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwt);

    private Message<byte[]> connect(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        // Inbound-channel messages are mutable so interceptors can set the user Principal.
        accessor.setLeaveMutable(true);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithoutTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(connect(null), null));
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> interceptor.preSend(connect("Bearer not-a-real-token"), null));
    }

    @Test
    void connectWithValidTokenSetsAccountPrincipal() {
        String token = jwt.issueAccessToken(42L, "u@test.local", "OWNER");
        Message<?> out = interceptor.preSend(connect("Bearer " + token), null);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(out);
        assertNotNull(accessor.getUser());
        assertEquals("42", accessor.getUser().getName());
    }
}

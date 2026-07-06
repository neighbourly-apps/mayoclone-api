package com.mayoclone.config;

import com.mayoclone.security.AccountPrincipal;
import com.mayoclone.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Authenticates the STOMP CONNECT frame from its {@code Authorization: Bearer <jwt>}
 * native header via {@link JwtService}. On success the STOMP session Principal is
 * set to the tenant's accountId (as a String), so
 * {@code convertAndSendToUser(accountId, "/queue/orders", …)} reaches only that
 * tenant's subscribers. A CONNECT without a valid token is rejected.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            AccountPrincipal principal = null;
            if (header != null && header.startsWith(BEARER)) {
                principal = jwtService.parse(header.substring(BEARER.length()).trim());
            }
            if (principal == null) {
                throw new IllegalArgumentException("STOMP CONNECT requires a valid Bearer token");
            }
            accessor.setUser(new StompPrincipal(String.valueOf(principal.id())));
        }
        return message;
    }

    /** Minimal Principal whose name is the tenant's accountId (used for user-destination routing). */
    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}

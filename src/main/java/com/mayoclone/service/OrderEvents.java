package com.mayoclone.service;

import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes per-tenant order events over STOMP to {@code /user/queue/orders}.
 *
 * <p>The messaging template is injected optionally so pure unit tests that build
 * this without a WebSocket context (template {@code null}) safely no-op. In the
 * full application context the broker's {@link SimpMessagingTemplate} is present.
 */
@Service
public class OrderEvents {

    /** STOMP user-queue the SPA subscribes to (prefixed with /user per principal). */
    static final String DESTINATION = "/queue/orders";

    private final SimpMessagingTemplate template;

    public OrderEvents(@Autowired(required = false) SimpMessagingTemplate template) {
        this.template = template;
    }

    public void newOrder(Long accountId, OrderDto order) {
        publish(accountId, OrderEvent.NEW_ORDER, order);
    }

    public void statusChanged(Long accountId, OrderDto order) {
        publish(accountId, OrderEvent.STATUS_CHANGED, order);
    }

    public void assigned(Long accountId, OrderDto order) {
        publish(accountId, OrderEvent.ASSIGNED, order);
    }

    private void publish(Long accountId, String type, OrderDto order) {
        if (template == null || accountId == null || order == null) {
            return;
        }
        template.convertAndSendToUser(String.valueOf(accountId), DESTINATION, new OrderEvent(type, order));
    }
}

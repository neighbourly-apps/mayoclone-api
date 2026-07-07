package com.mayoclone.service;

import com.mayoclone.domain.NotificationSetting;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderEvent;
import com.mayoclone.repository.NotificationSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-gated, failure-tolerant outbound webhook dispatcher.
 *
 * <p>When an order event fires (via {@link OrderEvents}), if the account has a
 * {@code webhookUrl} configured and the matching toggle is on, a compact JSON
 * payload is POSTed to that URL. The POST runs on a separate thread ({@code @Async})
 * with a short timeout and NEVER propagates an error — it logs and swallows — so it
 * can never block or fail the order flow. No-op when unconfigured.
 *
 * <p>This webhook is the seam that future WhatsApp/SMS providers layer on top of.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationSettingRepository settingsRepo;
    private final RestClient restClient;

    public NotificationDispatcher(NotificationSettingRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Asynchronously deliver the event to the account's webhook, if configured and
     * the matching toggle is enabled. Any error is logged and swallowed.
     */
    @Async
    public void dispatch(Long accountId, String eventType, OrderDto order) {
        try {
            if (accountId == null || order == null) {
                return;
            }
            NotificationSetting s = settingsRepo.findByAccountId(accountId).orElse(null);
            if (s == null || s.getWebhookUrl() == null || s.getWebhookUrl().isBlank()) {
                return; // unconfigured -> no-op
            }
            if (!enabledFor(s, eventType, order)) {
                return;
            }
            restClient.post()
                    .uri(s.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(eventType, order))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            // Never let a webhook failure affect the order flow.
            log.warn("Notification webhook dispatch failed for account {} event {}: {}",
                    accountId, eventType, ex.toString());
        }
    }

    private static boolean enabledFor(NotificationSetting s, String eventType, OrderDto order) {
        return switch (eventType) {
            case OrderEvent.NEW_ORDER -> s.isOnNewOrder();
            case OrderEvent.ASSIGNED -> s.isOnAssigned();
            // Cancellations arrive as STATUS_CHANGED with status CANCELLED.
            case OrderEvent.STATUS_CHANGED -> s.isOnCancelled() && order.status() == OrderStatus.CANCELLED;
            default -> false;
        };
    }

    private static Map<String, Object> payload(String eventType, OrderDto o) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", o.id());
        order.put("externalOrderId", o.externalOrderId());
        order.put("trainNumber", o.trainNumber());
        order.put("deliveryStationCode", o.deliveryStationCode());
        order.put("passengerName", o.passengerName());
        order.put("amount", o.amount());
        order.put("status", o.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", eventType);
        body.put("order", order);
        return body;
    }
}

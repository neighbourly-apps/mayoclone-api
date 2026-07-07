package com.mayoclone.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code PUT /api/notifications/settings} (full upsert). {@code webhookUrl}
 * is optional (null/blank => notifications disabled). Toggles absent in the body
 * default to {@code false}.
 */
public record UpdateNotificationSettingRequest(
        @Size(max = 1024) String webhookUrl,
        boolean onNewOrder,
        boolean onAssigned,
        boolean onCancelled
) {
}

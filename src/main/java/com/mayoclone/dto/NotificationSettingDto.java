package com.mayoclone.dto;

import com.mayoclone.domain.NotificationSetting;

import java.time.Instant;

/** Read view of an account's notification settings. */
public record NotificationSettingDto(
        String webhookUrl,
        boolean onNewOrder,
        boolean onAssigned,
        boolean onCancelled,
        Instant updatedAt
) {

    public static NotificationSettingDto from(NotificationSetting s) {
        return new NotificationSettingDto(
                s.getWebhookUrl(),
                s.isOnNewOrder(),
                s.isOnAssigned(),
                s.isOnCancelled(),
                s.getUpdatedAt());
    }
}

package com.mayoclone.web;

import com.mayoclone.dto.NotificationSettingDto;
import com.mayoclone.dto.UpdateNotificationSettingRequest;
import com.mayoclone.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped notification (outbound webhook) settings. */
@RestController
@RequestMapping("/api/notifications/settings")
public class NotificationSettingController {

    private final NotificationService notificationService;

    public NotificationSettingController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationSettingDto get() {
        return notificationService.get();
    }

    @PutMapping
    public NotificationSettingDto update(@Valid @RequestBody UpdateNotificationSettingRequest req) {
        return notificationService.update(req);
    }
}

package com.mayoclone.service;

import com.mayoclone.domain.NotificationSetting;
import com.mayoclone.dto.NotificationSettingDto;
import com.mayoclone.dto.UpdateNotificationSettingRequest;
import com.mayoclone.repository.NotificationSettingRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Tenant-scoped CRUD over the per-account {@link NotificationSetting}. Reading
 * creates a defaults row on first access so the endpoint always returns a value.
 */
@Service
public class NotificationService {

    private final NotificationSettingRepository repo;
    private final CurrentAccountService currentAccount;

    public NotificationService(NotificationSettingRepository repo, CurrentAccountService currentAccount) {
        this.repo = repo;
        this.currentAccount = currentAccount;
    }

    /** The account's settings, creating defaults (onNewOrder=true) if absent. */
    @Transactional
    public NotificationSettingDto get() {
        return NotificationSettingDto.from(getOrCreate(currentAccount.accountId()));
    }

    /** Full upsert of the account's settings. */
    @Transactional
    public NotificationSettingDto update(UpdateNotificationSettingRequest req) {
        NotificationSetting s = getOrCreate(currentAccount.accountId());
        String url = req.webhookUrl() == null || req.webhookUrl().isBlank() ? null : req.webhookUrl().trim();
        if (url != null) {
            // SSRF guard: reject a webhook aimed at internal infra (metadata/RFC1918/loopback).
            try {
                WebhookUrlGuard.validateForSave(url);
            } catch (WebhookUrlGuard.InvalidWebhookUrlException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }
        s.setWebhookUrl(url);
        s.setOnNewOrder(req.onNewOrder());
        s.setOnAssigned(req.onAssigned());
        s.setOnCancelled(req.onCancelled());
        s.setUpdatedAt(Instant.now());
        return NotificationSettingDto.from(repo.save(s));
    }

    private NotificationSetting getOrCreate(Long accountId) {
        return repo.findByAccountId(accountId).orElseGet(() -> {
            NotificationSetting s = new NotificationSetting();
            s.setAccountId(accountId);
            s.setOnNewOrder(true);
            s.setOnAssigned(false);
            s.setOnCancelled(false);
            s.setUpdatedAt(Instant.now());
            return repo.save(s);
        });
    }
}

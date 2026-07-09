package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Manages the Gmail {@code users.watch} lifecycle for connected mailboxes:
 * start (on connect), renew (before the ~7-day expiry), and stop (on removal).
 * Everything is gated on a configured Pub/Sub topic
 * ({@link GmailPushProperties#pushConfigured()}) and is best-effort — a watch
 * failure NEVER breaks the connect/delete flow (the mailbox simply keeps being
 * polled as a fallback).
 */
@Service
public class GmailWatchService {

    private static final Logger log = LoggerFactory.getLogger(GmailWatchService.class);

    private final GmailPushProperties props;
    private final GmailOAuthService oauth;
    private final GmailApiClient gmail;
    private final VendorRepository vendorRepo;

    public GmailWatchService(GmailPushProperties props,
                             GmailOAuthService oauth,
                             GmailApiClient gmail,
                             VendorRepository vendorRepo) {
        this.props = props;
        this.oauth = oauth;
        this.gmail = gmail;
        this.vendorRepo = vendorRepo;
    }

    /** True when a Pub/Sub topic is configured → the watch lifecycle is active. */
    public boolean pushEnabled() {
        return props.pushConfigured();
    }

    /** React to a fresh Gmail connect by starting a watch (adopting the returned historyId as baseline). */
    @EventListener
    public void onGmailConnected(GmailConnectedEvent event) {
        Vendor vendor = vendorRepo.findById(event.vendorId()).orElse(null);
        if (vendor != null) {
            startWatch(vendor, true);
        }
    }

    /**
     * Register/refresh a {@code users.watch} for the mailbox and persist the returned
     * expiration. When {@code adoptHistoryId} is true (initial connect) the returned
     * historyId becomes the sync baseline; on renewal we keep any existing baseline so
     * we never skip messages, only adopting the returned id when none is set yet.
     */
    public void startWatch(Vendor vendor, boolean adoptHistoryId) {
        if (!pushEnabled() || !oauth.isEnabled()
                || vendor.getSourceType() != MailSourceType.GMAIL_OAUTH
                || vendor.getOauthRefreshToken() == null) {
            return; // feature off / not a connected Gmail mailbox — nothing to do
        }
        try {
            String accessToken = oauth.refreshAccessToken(vendor.getOauthRefreshToken());
            if (accessToken == null) {
                return;
            }
            GmailApiClient.WatchResult result = gmail.watch(accessToken, props.pubsubTopic());
            if (adoptHistoryId || vendor.getGmailHistoryId() == null || vendor.getGmailHistoryId().isBlank()) {
                if (result.historyId() != null && !result.historyId().isBlank()) {
                    vendor.setGmailHistoryId(result.historyId());
                }
            }
            if (result.expiration() > 0) {
                vendor.setGmailWatchExpiration(Instant.ofEpochMilli(result.expiration()));
            }
            vendorRepo.save(vendor);
            log.info("Gmail watch started for vendor {} (expires {})",
                    vendor.getId(), vendor.getGmailWatchExpiration());
        } catch (RuntimeException e) {
            log.warn("Gmail watch start failed for vendor {} (will keep polling): {}",
                    vendor.getId(), e.getMessage());
        }
    }

    /** Best-effort {@code users.stop} when a Gmail mailbox is removed. */
    public void stopWatch(Vendor vendor) {
        if (!oauth.isEnabled()
                || vendor.getSourceType() != MailSourceType.GMAIL_OAUTH
                || vendor.getOauthRefreshToken() == null) {
            return;
        }
        try {
            String accessToken = oauth.refreshAccessToken(vendor.getOauthRefreshToken());
            if (accessToken != null) {
                gmail.stop(accessToken);
                log.info("Gmail watch stopped for vendor {}", vendor.getId());
            }
        } catch (RuntimeException e) {
            log.warn("Gmail watch stop failed for vendor {}: {}", vendor.getId(), e.getMessage());
        }
    }
}

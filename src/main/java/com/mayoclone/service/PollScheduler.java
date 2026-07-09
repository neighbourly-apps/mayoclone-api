package com.mayoclone.service;

import com.mayoclone.dto.IngestResult;
import com.mayoclone.ingest.gmail.GmailPushProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically sweeps all active mailboxes. Disabled by default: when
 * {@code mayoclone.poll.enabled} is false the scheduled method early-returns
 * and never touches a mailbox.
 *
 * <p><b>Push interplay.</b> When a Gmail Pub/Sub topic is configured and
 * {@code mayoclone.poll.skip-gmail-when-push} is true (default), the poll SKIPS
 * GMAIL_OAUTH vendors — those mailboxes are driven by push instead. IMAP/other
 * vendors are always polled. With no topic configured, Gmail vendors are polled
 * as before, so the poll remains a complete fallback.
 */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final ImapIngestionService ingestionService;
    private final GmailPushProperties pushProps;
    private final boolean enabled;

    public PollScheduler(ImapIngestionService ingestionService,
                         GmailPushProperties pushProps,
                         @Value("${mayoclone.poll.enabled:false}") boolean enabled) {
        this.ingestionService = ingestionService;
        this.pushProps = pushProps;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${mayoclone.poll.interval-ms:60000}")
    public void poll() {
        if (!enabled) {
            return; // polling disabled — do nothing
        }
        try {
            boolean skipGmail = pushProps.pushConfigured() && pushProps.skipGmailWhenPush();
            IngestResult result = ingestionService.syncAllActive(skipGmail);
            log.info("Scheduled poll: fetched={} newOrders={} (gmail-skipped={})",
                    result.fetched(), result.newOrders(), skipGmail);
        } catch (RuntimeException e) {
            log.warn("Scheduled poll failed: {}", e.getMessage());
        }
    }
}

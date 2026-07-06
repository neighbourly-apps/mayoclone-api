package com.mayoclone.service;

import com.mayoclone.dto.IngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically sweeps all active mailboxes. Disabled by default: when
 * {@code mayoclone.poll.enabled} is false the scheduled method early-returns
 * and never touches a mailbox.
 */
@Component
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final ImapIngestionService ingestionService;
    private final boolean enabled;

    public PollScheduler(ImapIngestionService ingestionService,
                         @Value("${mayoclone.poll.enabled:false}") boolean enabled) {
        this.ingestionService = ingestionService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${mayoclone.poll.interval-ms:60000}")
    public void poll() {
        if (!enabled) {
            return; // polling disabled — do nothing
        }
        try {
            IngestResult result = ingestionService.syncAllActive();
            log.info("Scheduled poll: fetched={} newOrders={}", result.fetched(), result.newOrders());
        } catch (RuntimeException e) {
            log.warn("Scheduled poll failed: {}", e.getMessage());
        }
    }
}

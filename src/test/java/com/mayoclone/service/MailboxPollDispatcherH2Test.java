package com.mayoclone.service;

import com.mayoclone.domain.IngestJobStatus;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.gmail.GmailPushProperties;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.jobs.MailboxSyncJobHandler;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.IngestJobRepository;
import com.mayoclone.repository.VendorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed (no Docker) test of {@link MailboxPollDispatcher}. Drives one dispatch pass
 * directly against the real {@link VendorRepository} + {@link JobQueue} (scheduler off
 * in the test profile). Proves the due/pollable selection, the {@code mbsync:<id>}
 * dedupKey, that next_poll_at is advanced so a vendor isn't re-selected, and the
 * Gmail-when-push exclusion. The advisory lock is Postgres-only and skipped on H2
 * (proven in the Docker-gated suite).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MailboxPollDispatcherH2Test {

    @Autowired
    private VendorRepository vendorRepo;

    @Autowired
    private JobQueue jobQueue;

    @Autowired
    private IngestJobRepository jobRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long INTERVAL_MS = 60_000L;
    private static final List<IngestJobStatus> ACTIVE =
            List.of(IngestJobStatus.PENDING, IngestJobStatus.RUNNING);

    /** Gmail push OFF (no topic) → gmailPollable = true. */
    private MailboxPollDispatcher dispatcher(boolean pushConfigured) {
        String topic = pushConfigured ? "projects/p/topics/gmail-push" : "";
        GmailPushProperties props = new GmailPushProperties(topic, "", "", 21_600_000L, true);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        return new MailboxPollDispatcher(vendorRepo, jobQueue, props, jdbc,
                new AppMetrics(reg), reg, true, INTERVAL_MS, 200);
    }

    private Vendor saveVendor(MailSourceType type, boolean active, Instant nextPollAt) {
        Vendor v = new Vendor();
        v.setAccountId(1L);
        v.setRestaurantName("R-" + System.nanoTime());
        v.setSourceType(type);
        v.setActive(active);
        v.setNextPollAt(nextPollAt);
        v.setCreatedAt(Instant.now());
        return vendorRepo.saveAndFlush(v);
    }

    private boolean hasMailboxSyncJob(Long vendorId) {
        return jobRepo.findFirstByDedupKeyAndStatusInOrderByIdAsc("mbsync:" + vendorId, ACTIVE)
                .isPresent();
    }

    @Test
    void selectsOnlyDuePollableVendorsAndEnqueuesWithDedupKey() {
        Vendor dueImap = saveVendor(MailSourceType.IMAP, true, null);            // due (never polled)
        Vendor future = saveVendor(MailSourceType.IMAP, true, Instant.now().plusSeconds(3600)); // not due
        Vendor forwarding = saveVendor(MailSourceType.FORWARDING, true, null);   // push-only, excluded
        Vendor inactive = saveVendor(MailSourceType.IMAP, false, null);          // excluded
        Vendor dueGmail = saveVendor(MailSourceType.GMAIL_OAUTH, true, null);    // pollable (push off)

        int dispatched = dispatcher(false).dispatchDue();

        assertEquals(2, dispatched, "only the two due, pollable vendors are dispatched");
        assertTrue(hasMailboxSyncJob(dueImap.getId()));
        assertTrue(hasMailboxSyncJob(dueGmail.getId()));
        assertFalse(hasMailboxSyncJob(future.getId()), "future next_poll_at → not due");
        assertFalse(hasMailboxSyncJob(forwarding.getId()), "FORWARDING is push-only");
        assertFalse(hasMailboxSyncJob(inactive.getId()), "inactive excluded");

        // A dispatched vendor's next_poll_at is advanced so it isn't re-selected.
        Vendor reloaded = vendorRepo.findById(dueImap.getId()).orElseThrow();
        assertNotNull(reloaded.getNextPollAt());
        assertTrue(reloaded.getNextPollAt().isAfter(Instant.now().plusMillis(INTERVAL_MS - 5_000)),
                "next_poll_at advanced ~ now + interval");
        // FORWARDING was never selected → its next_poll_at stays null.
        assertNull(vendorRepo.findById(forwarding.getId()).orElseThrow().getNextPollAt());
    }

    @Test
    void gmailVendorsExcludedWhenPushConfigured() {
        Vendor dueImap = saveVendor(MailSourceType.IMAP, true, null);
        Vendor dueGmail = saveVendor(MailSourceType.GMAIL_OAUTH, true, null);

        int dispatched = dispatcher(true).dispatchDue(); // push configured → gmail not pollable

        assertEquals(1, dispatched);
        assertTrue(hasMailboxSyncJob(dueImap.getId()));
        assertFalse(hasMailboxSyncJob(dueGmail.getId()), "Gmail handled by push, not polled");
    }

    @Test
    void advancingNextPollAtPreventsReSelectionOnTheNextTick() {
        Vendor v = saveVendor(MailSourceType.IMAP, true, null);
        MailboxPollDispatcher d = dispatcher(false);

        assertEquals(1, d.dispatchDue(), "first tick selects the due vendor");
        assertEquals(0, d.dispatchDue(), "second tick: next_poll_at now in the future → not re-selected");
    }
}

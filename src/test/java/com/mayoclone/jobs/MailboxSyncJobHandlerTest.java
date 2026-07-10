package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.ImapIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code MAILBOX_SYNC} handler against mocked collaborators (no DB).
 * Covers success (clears failure + reschedules), the expected-mailbox-error path
 * (records + backs off, does NOT rethrow → job DONE), infra rethrow, the clean skips,
 * and the monotonic-capped backoff schedule.
 */
class MailboxSyncJobHandlerTest {

    private static final long INTERVAL_MS = 60_000L;
    private static final long CAP_MS = 3_600_000L;

    private VendorRepository vendorRepo;
    private ImapIngestionService ingestionService;
    private MailboxSyncJobHandler handler;

    @BeforeEach
    void setup() {
        vendorRepo = mock(VendorRepository.class);
        ingestionService = mock(ImapIngestionService.class);
        AppMetrics metrics = mock(AppMetrics.class);
        handler = new MailboxSyncJobHandler(vendorRepo, ingestionService, metrics, INTERVAL_MS, CAP_MS);
    }

    private static Vendor vendor(MailSourceType type, boolean active) {
        Vendor v = new Vendor();
        v.setId(42L);
        v.setAccountId(7L);
        v.setSourceType(type);
        v.setActive(active);
        return v;
    }

    private static IngestJob jobFor(Long vendorId) {
        IngestJob j = new IngestJob();
        j.setVendorId(vendorId);
        j.setPayload("{}");
        return j;
    }

    @Test
    void jobType_isMailboxSync() {
        assertEquals("MAILBOX_SYNC", handler.jobType());
    }

    // ------------------------------------------------------------------ success

    @Test
    void successClearsFailureStateAndSchedulesNextPoll() {
        Vendor v = vendor(MailSourceType.IMAP, true);
        v.setPollFailureCount(3);
        v.setLastSyncError("old error");
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));

        Instant before = Instant.now();
        handler.handle(jobFor(42L));

        verify(ingestionService).syncVendor(v);
        assertEquals(0, v.getPollFailureCount());
        assertNull(v.getLastSyncError());
        assertNotNull(v.getNextPollAt());
        assertTrue(v.getNextPollAt().isAfter(before.plusMillis(INTERVAL_MS - 1000)),
                "next_poll_at ≈ now + interval");
        verify(vendorRepo).save(v);
    }

    // ------------------------------------------------------ expected mailbox error

    @Test
    void mailboxErrorRecordsFailureBacksOffAndDoesNotRethrow() {
        Vendor v = vendor(MailSourceType.IMAP, true);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(ingestionService.syncVendor(v))
                .thenThrow(new RuntimeException("IMAP AUTHENTICATE failed"));

        Instant before = Instant.now();
        handler.handle(jobFor(42L)); // MUST NOT throw — job completes DONE

        assertEquals(1, v.getPollFailureCount());
        assertEquals("IMAP AUTHENTICATE failed", v.getLastSyncError());
        assertNotNull(v.getNextPollAt());
        // failures=1 → backoff = interval << 1 = 120s in the future.
        assertTrue(v.getNextPollAt().isAfter(before.plusMillis(INTERVAL_MS)),
                "next_poll_at pushed out by exponential backoff");
        verify(vendorRepo).save(v);
    }

    @Test
    void mailboxErrorMessageIsTruncatedTo500() {
        Vendor v = vendor(MailSourceType.IMAP, true);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(ingestionService.syncVendor(v)).thenThrow(new RuntimeException("x".repeat(2000)));

        handler.handle(jobFor(42L));

        assertEquals(500, v.getLastSyncError().length());
    }

    @Test
    void infraErrorRethrowsSoTheQueueRetries() {
        Vendor v = vendor(MailSourceType.IMAP, true);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(ingestionService.syncVendor(v))
                .thenThrow(new RecoverableDataAccessException("db down"));

        assertThrows(RecoverableDataAccessException.class, () -> handler.handle(jobFor(42L)));
        // No vendor-level backoff written on an infra error — the queue owns that retry.
        verify(vendorRepo, never()).save(any());
    }

    // ----------------------------------------------------------------- skips

    @Test
    void forwardingVendorIsCleanNoOp() {
        Vendor v = vendor(MailSourceType.FORWARDING, true);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));

        handler.handle(jobFor(42L));

        verify(ingestionService, never()).syncVendor(any());
        verify(vendorRepo, never()).save(any());
    }

    @Test
    void inactiveVendorIsCleanNoOp() {
        Vendor v = vendor(MailSourceType.IMAP, false);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));

        handler.handle(jobFor(42L));

        verify(ingestionService, never()).syncVendor(any());
        verify(vendorRepo, never()).save(any());
    }

    @Test
    void missingVendorIsCleanNoOp() {
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());
        handler.handle(jobFor(99L));
        verify(ingestionService, never()).syncVendor(any());
    }

    @Test
    void nullVendorIdIsCleanNoOp() {
        handler.handle(jobFor(null));
        verify(vendorRepo, never()).findById(any());
    }

    // ----------------------------------------------------------------- backoff

    @Test
    void backoffIsMonotonicNonDecreasingAndCapped() {
        long prev = 0;
        for (int f = 1; f <= 10; f++) {
            long b = handler.backoffMs(f);
            assertTrue(b >= prev, "backoff must be non-decreasing (f=" + f + ")");
            assertTrue(b <= CAP_MS, "backoff must be capped (f=" + f + ")");
            prev = b;
        }
        assertEquals(INTERVAL_MS << 1, handler.backoffMs(1), "first backoff = interval * 2");
        assertEquals(CAP_MS, handler.backoffMs(6), "high failure count saturates at the cap");
        assertEquals(CAP_MS, handler.backoffMs(50), "stays capped");
    }
}

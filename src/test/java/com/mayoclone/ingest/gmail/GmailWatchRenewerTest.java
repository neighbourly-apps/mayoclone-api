package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.ProcessedPushRepository;
import com.mayoclone.repository.VendorRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Selection logic of the watch renewer: only expiring/null-watch mailboxes are re-watched. */
class GmailWatchRenewerTest {

    private static GmailPushProperties props(String topic) {
        return new GmailPushProperties(topic, "", "", 21600000L, true);
    }

    private static Vendor vendor(long id, Instant watchExpiration) {
        Vendor v = new Vendor();
        v.setId(id);
        v.setSourceType(MailSourceType.GMAIL_OAUTH);
        v.setActive(true);
        v.setGmailWatchExpiration(watchExpiration);
        return v;
    }

    @Test
    void noTopicConfigured_sweepIsNoOp() {
        GmailWatchService watch = mock(GmailWatchService.class);
        VendorRepository vendorRepo = mock(VendorRepository.class);
        GmailWatchRenewer renewer = new GmailWatchRenewer(
                props(""), watch, vendorRepo, mock(ProcessedPushRepository.class));

        renewer.renew();

        verify(vendorRepo, never()).findByActiveTrueAndSourceType(MailSourceType.GMAIL_OAUTH);
        verify(watch, never()).startWatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void renewsOnlyExpiringOrNullWatchMailboxes() {
        GmailWatchService watch = mock(GmailWatchService.class);
        VendorRepository vendorRepo = mock(VendorRepository.class);

        Vendor healthy = vendor(1L, Instant.now().plus(5, ChronoUnit.DAYS));      // far future → skip
        Vendor expiringSoon = vendor(2L, Instant.now().plus(3, ChronoUnit.HOURS)); // within 24h → renew
        Vendor expired = vendor(3L, Instant.now().minus(1, ChronoUnit.HOURS));     // expired → renew
        Vendor neverWatched = vendor(4L, null);                                    // null → renew

        when(vendorRepo.findByActiveTrueAndSourceType(MailSourceType.GMAIL_OAUTH))
                .thenReturn(List.of(healthy, expiringSoon, expired, neverWatched));

        GmailWatchRenewer renewer = new GmailWatchRenewer(
                props("projects/p/topics/t"), watch, vendorRepo, mock(ProcessedPushRepository.class));

        int renewed = renewer.renewExpiring();

        assertEquals(3, renewed);
        verify(watch, never()).startWatch(healthy, false);
        verify(watch).startWatch(expiringSoon, false);
        verify(watch).startWatch(expired, false);
        verify(watch).startWatch(neverWatched, false);
    }
}

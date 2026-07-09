package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.MimeEmailParser;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the GMAIL_HISTORY handler mapping logic against a MOCKED Gmail client
 * (no network): history.list → messages.get → IngestionCore, historyId advance,
 * the 404 rebaseline fallback, the cold-start path, and the clean skips.
 */
class GmailHistoryJobHandlerTest {

    private VendorRepository vendorRepo;
    private GmailOAuthService oauth;
    private GmailApiClient gmail;
    private IngestionCore ingestionCore;
    private GmailHistoryJobHandler handler;

    @BeforeEach
    void setup() {
        vendorRepo = mock(VendorRepository.class);
        oauth = mock(GmailOAuthService.class);
        gmail = mock(GmailApiClient.class);
        ingestionCore = mock(IngestionCore.class);
        handler = new GmailHistoryJobHandler(vendorRepo, oauth, gmail, ingestionCore, new MimeEmailParser());

        when(oauth.isEnabled()).thenReturn(true);
        when(oauth.refreshAccessToken("refresh-tok")).thenReturn("access-tok");
    }

    private static Vendor connectedVendor(String historyId) {
        Vendor v = new Vendor();
        v.setId(42L);
        v.setAccountId(7L);
        v.setSourceType(MailSourceType.GMAIL_OAUTH);
        v.setActive(true);
        v.setOauthEmail("shop@gmail.com");
        v.setOauthRefreshToken("refresh-tok");
        v.setGmailHistoryId(historyId);
        return v;
    }

    private static IngestJob jobFor(long vendorId) {
        IngestJob job = new IngestJob();
        job.setVendorId(vendorId);
        job.setPayload("{\"historyId\":\"999\"}");
        return job;
    }

    private static byte[] rawEmail() {
        String eml = "From: orders@zoopindia.com\r\n"
                + "Subject: Your order\r\n"
                + "Message-ID: <abc-123@zoopindia.com>\r\n\r\n"
                + "Order body text.\r\n";
        return eml.getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- happy path

    @Test
    void historyListIngestsAddedMessagesAndAdvancesHistoryId() throws Exception {
        Vendor v = connectedVendor("100");
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(gmail.listHistory("access-tok", "100", null))
                .thenReturn(new GmailApiClient.HistoryPage(List.of("m1"), null, "200"));
        when(gmail.getMessageRaw("access-tok", "m1")).thenReturn(rawEmail());

        handler.handle(jobFor(42L));

        ArgumentCaptor<RawMessage> msg = ArgumentCaptor.forClass(RawMessage.class);
        verify(ingestionCore).process(eq(7L), eq(42L), eq(MailSourceType.GMAIL_OAUTH), msg.capture());
        assertEquals("orders@zoopindia.com", msg.getValue().from());
        assertEquals("<abc-123@zoopindia.com>", msg.getValue().messageId());
        assertEquals("200", v.getGmailHistoryId()); // advanced
        verify(vendorRepo).save(v);
    }

    @Test
    void historyListPaginatesAcrossPages() throws Exception {
        Vendor v = connectedVendor("100");
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(gmail.listHistory("access-tok", "100", null))
                .thenReturn(new GmailApiClient.HistoryPage(List.of("m1"), "page2", "150"));
        when(gmail.listHistory("access-tok", "100", "page2"))
                .thenReturn(new GmailApiClient.HistoryPage(List.of("m2"), null, "200"));
        when(gmail.getMessageRaw(eq("access-tok"), any())).thenReturn(rawEmail());

        handler.handle(jobFor(42L));

        verify(ingestionCore, times(2)).process(eq(7L), eq(42L), eq(MailSourceType.GMAIL_OAUTH), any());
        assertEquals("200", v.getGmailHistoryId());
    }

    // ------------------------------------------------------------- 404 fallback

    @Test
    void historyGoneFallsBackToRecentInboxAndRebaselines() throws Exception {
        Vendor v = connectedVendor("100");
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(gmail.listHistory("access-tok", "100", null))
                .thenThrow(new GmailApiClient.HistoryGoneException("too old"));
        when(gmail.listInboxMessageIds("access-tok", 25)).thenReturn(List.of("r1"));
        when(gmail.getMessageRaw("access-tok", "r1")).thenReturn(rawEmail());
        when(gmail.profileHistoryId("access-tok")).thenReturn("500");

        handler.handle(jobFor(42L));

        verify(ingestionCore).process(eq(7L), eq(42L), eq(MailSourceType.GMAIL_OAUTH), any());
        assertEquals("500", v.getGmailHistoryId()); // rebaselined from profile
        verify(vendorRepo).save(v);
    }

    // ------------------------------------------------------------- cold start

    @Test
    void noBaselineHistoryIdRecoversRecentInbox() throws Exception {
        Vendor v = connectedVendor(null);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        when(gmail.listInboxMessageIds("access-tok", 25)).thenReturn(List.of("r1"));
        when(gmail.getMessageRaw("access-tok", "r1")).thenReturn(rawEmail());
        when(gmail.profileHistoryId("access-tok")).thenReturn("300");

        handler.handle(jobFor(42L));

        verify(gmail, never()).listHistory(any(), any(), any());
        verify(ingestionCore).process(eq(7L), eq(42L), eq(MailSourceType.GMAIL_OAUTH), any());
        assertEquals("300", v.getGmailHistoryId());
    }

    // ----------------------------------------------------------------- skips

    @Test
    void missingVendorIsCleanNoOp() throws Exception {
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());
        handler.handle(jobFor(99L));
        verify(ingestionCore, never()).process(any(), any(), any(), any());
    }

    @Test
    void inactiveVendorIsCleanNoOp() throws Exception {
        Vendor v = connectedVendor("100");
        v.setActive(false);
        when(vendorRepo.findById(42L)).thenReturn(Optional.of(v));
        handler.handle(jobFor(42L));
        verify(ingestionCore, never()).process(any(), any(), any(), any());
    }

    @Test
    void jobType_isGmailHistory() {
        assertEquals("GMAIL_HISTORY", handler.jobType());
    }
}

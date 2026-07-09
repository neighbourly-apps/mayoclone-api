package com.mayoclone.web;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.ProcessedPush;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.gmail.GmailHistoryJobHandler;
import com.mayoclone.ingest.gmail.GmailPushVerifier;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.repository.ProcessedPushRepository;
import com.mayoclone.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test of the Gmail push endpoint contract with a mocked verifier + repos +
 * queue (no Spring context, no network): status mapping, envelope decode/enqueue,
 * idempotency, and the unknown-email ack.
 */
class GmailPushControllerTest {

    private GmailPushVerifier verifier;
    private ProcessedPushRepository processedRepo;
    private VendorRepository vendorRepo;
    private JobQueue jobQueue;
    private GmailPushController controller;

    @BeforeEach
    void setup() {
        verifier = mock(GmailPushVerifier.class);
        processedRepo = mock(ProcessedPushRepository.class);
        vendorRepo = mock(VendorRepository.class);
        jobQueue = mock(JobQueue.class);
        controller = new GmailPushController(verifier, processedRepo, vendorRepo, jobQueue);
    }

    /** Build a Pub/Sub push envelope with the inner {emailAddress,historyId} data. */
    private static MockHttpServletRequest pushRequest(String messageId, String email, String historyId) {
        String inner = "{\"emailAddress\":\"" + email + "\",\"historyId\":\"" + historyId + "\"}";
        String data = Base64.getEncoder().encodeToString(inner.getBytes(StandardCharsets.UTF_8));
        String envelope = "{\"message\":{\"data\":\"" + data + "\",\"messageId\":\"" + messageId
                + "\",\"publishTime\":\"2026-07-06T00:00:00Z\"},\"subscription\":\"sub\"}";
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent(envelope.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private static Vendor gmailVendor(long id, long accountId, String email) {
        Vendor v = new Vendor();
        v.setId(id);
        v.setAccountId(accountId);
        v.setSourceType(MailSourceType.GMAIL_OAUTH);
        v.setOauthEmail(email);
        v.setActive(true);
        return v;
    }

    // ---------------------------------------------------------------- verification

    @Test
    void disabled_returns503() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.DISABLED);
        ResponseEntity<Void> res = controller.push(new MockHttpServletRequest());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verify(jobQueue, never()).enqueue(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void unauthorized_returns401() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.UNAUTHORIZED);
        ResponseEntity<Void> res = controller.push(new MockHttpServletRequest());
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        verify(jobQueue, never()).enqueue(anyString(), any(), any(), anyString(), anyString());
    }

    // ----------------------------------------------------------- decode + enqueue

    @Test
    void validKnownMailbox_enqueuesHistoryJobAnd204() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.OK);
        when(processedRepo.existsById("m-1")).thenReturn(false);
        when(vendorRepo.findFirstBySourceTypeAndOauthEmail(MailSourceType.GMAIL_OAUTH, "shop@gmail.com"))
                .thenReturn(Optional.of(gmailVendor(42L, 7L, "shop@gmail.com")));

        ResponseEntity<Void> res = controller.push(pushRequest("m-1", "shop@gmail.com", "5555"));

        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
        verify(jobQueue).enqueue(eq(GmailHistoryJobHandler.JOB_TYPE), eq(7L), eq(42L),
                eq("{\"historyId\":\"5555\"}"), eq("gmail-history:42"));
        verify(processedRepo).save(any(ProcessedPush.class));
    }

    // --------------------------------------------------------------- idempotency

    @Test
    void duplicateMessageId_204_noEnqueue() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.OK);
        when(processedRepo.existsById("m-dup")).thenReturn(true);

        ResponseEntity<Void> res = controller.push(pushRequest("m-dup", "shop@gmail.com", "5555"));

        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
        verify(jobQueue, never()).enqueue(anyString(), any(), any(), anyString(), anyString());
        verify(processedRepo, never()).save(any());
    }

    // --------------------------------------------------------------- unknown email

    @Test
    void unknownMailbox_204_noEnqueueButRecorded() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.OK);
        when(processedRepo.existsById("m-2")).thenReturn(false);
        when(vendorRepo.findFirstBySourceTypeAndOauthEmail(MailSourceType.GMAIL_OAUTH, "nobody@gmail.com"))
                .thenReturn(Optional.empty());

        ResponseEntity<Void> res = controller.push(pushRequest("m-2", "nobody@gmail.com", "9"));

        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
        verify(jobQueue, never()).enqueue(anyString(), any(), any(), anyString(), anyString());
        verify(processedRepo).save(any(ProcessedPush.class)); // still recorded to skip re-lookup
    }

    // ----------------------------------------------------------------- malformed

    @Test
    void malformedBody_returns400() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.OK);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{not-json".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<Void> res = controller.push(req);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    void missingMessage_returns400() throws Exception {
        when(verifier.verify(any())).thenReturn(GmailPushVerifier.Result.OK);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{\"subscription\":\"s\"}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<Void> res = controller.push(req);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }
}

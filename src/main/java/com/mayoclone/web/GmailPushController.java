package com.mayoclone.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.ProcessedPush;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.gmail.GmailHistoryJobHandler;
import com.mayoclone.ingest.gmail.GmailPushVerifier;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.repository.ProcessedPushRepository;
import com.mayoclone.repository.VendorRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Google Pub/Sub PUSH endpoint for Gmail change notifications:
 * {@code POST /api/inbound/gmail/push} (public route; the endpoint does its own
 * verification). Delivered envelope:
 * <pre>
 * { "message": { "data": "&lt;base64 of {emailAddress,historyId}&gt;",
 *                "messageId": "...", "publishTime": "..." },
 *   "subscription": "..." }
 * </pre>
 *
 * <p><b>Verification</b> ({@link GmailPushVerifier}): OIDC Bearer JWT when an
 * audience is configured, else a shared {@code ?token=}, else the endpoint is
 * disabled. Invalid auth → 401; disabled → 503.
 *
 * <p><b>On a valid request</b>: decode the envelope, dedup on
 * {@code message.messageId} ({@code processed_push} ledger), resolve the
 * GMAIL_OAUTH vendor by {@code emailAddress}, and enqueue a coalesced
 * {@code GMAIL_HISTORY} job. Returns <b>204</b> quickly for anything safely
 * handled (including an unknown email — ack + ignore, no leak); <b>400</b> for a
 * permanently unprocessable body; transient failures surface as 5xx so Pub/Sub
 * retries.
 */
@RestController
@RequestMapping("/api/inbound")
public class GmailPushController {

    private static final Logger log = LoggerFactory.getLogger(GmailPushController.class);

    private final GmailPushVerifier verifier;
    private final ProcessedPushRepository processedRepo;
    private final VendorRepository vendorRepo;
    private final JobQueue jobQueue;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GmailPushController(GmailPushVerifier verifier,
                               ProcessedPushRepository processedRepo,
                               VendorRepository vendorRepo,
                               JobQueue jobQueue) {
        this.verifier = verifier;
        this.processedRepo = processedRepo;
        this.vendorRepo = vendorRepo;
        this.jobQueue = jobQueue;
    }

    @PostMapping("/gmail/push")
    public ResponseEntity<Void> push(HttpServletRequest request) throws IOException {
        switch (verifier.verify(request)) {
            case DISABLED -> {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            case UNAUTHORIZED -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            default -> { /* OK — fall through */ }
        }

        String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);

        String pushMessageId;
        String emailAddress;
        String historyId;
        try {
            Map<String, Object> envelope = objectMapper.readValue(rawBody.isBlank() ? "{}" : rawBody, Map.class);
            Object messageObj = envelope.get("message");
            if (!(messageObj instanceof Map<?, ?> message)) {
                return badRequest("missing message");
            }
            pushMessageId = str(message.get("messageId"));
            String data = str(message.get("data"));
            if (pushMessageId == null || data == null) {
                return badRequest("missing messageId/data");
            }
            byte[] decoded = Base64.getDecoder().decode(data);
            Map<String, Object> payload = objectMapper.readValue(decoded, Map.class);
            emailAddress = str(payload.get("emailAddress"));
            historyId = str(payload.get("historyId"));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            // Bad base64 / malformed JSON — permanently unprocessable.
            return badRequest("malformed envelope: " + e.getMessage());
        }

        if (emailAddress == null || emailAddress.isBlank()) {
            return badRequest("missing emailAddress");
        }

        // Idempotency: skip re-doing work for a re-delivered Pub/Sub message.
        if (processedRepo.existsById(pushMessageId)) {
            log.debug("Gmail push {} already processed — acking", pushMessageId);
            return noContent();
        }

        Vendor vendor = vendorRepo
                .findFirstBySourceTypeAndOauthEmail(MailSourceType.GMAIL_OAUTH, emailAddress)
                .orElse(null);

        if (vendor != null) {
            // Coalesce a burst of pushes for this mailbox into a single history sync.
            String payloadJson = "{\"historyId\":" + jsonValue(historyId) + "}";
            jobQueue.enqueue(GmailHistoryJobHandler.JOB_TYPE, vendor.getAccountId(), vendor.getId(),
                    payloadJson, "gmail-history:" + vendor.getId());
            log.info("Gmail push {} enqueued history sync for vendor {}", pushMessageId, vendor.getId());
        } else {
            // Unknown mailbox — ack + ignore (do not reveal which addresses exist).
            log.debug("Gmail push {} for unknown mailbox — ignored", pushMessageId);
        }

        // Record AFTER enqueue: correctness is guaranteed by dedup_key coalescing +
        // Message-ID dedup, so a re-delivery before this row commits is harmless.
        recordProcessed(pushMessageId);
        return noContent();
    }

    private void recordProcessed(String pushMessageId) {
        try {
            processedRepo.save(new ProcessedPush(pushMessageId, Instant.now()));
        } catch (DataIntegrityViolationException race) {
            // Concurrent delivery already inserted the same id — fine.
            log.debug("Gmail push {} already recorded (race)", pushMessageId);
        }
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> badRequest(String why) {
        log.debug("Gmail push rejected (400): {}", why);
        return ResponseEntity.badRequest().build();
    }

    /** JSON-encode a historyId as a quoted string, or {@code null} when absent. */
    private static String jsonValue(String historyId) {
        return historyId == null ? "null" : "\"" + historyId.replace("\"", "") + "\"";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

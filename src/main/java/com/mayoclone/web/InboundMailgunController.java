package com.mayoclone.web;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.MimeEmailParser;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.ingest.inbound.MailgunSignatureVerifier;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REAL Mailgun inbound adapter. A Mailgun <b>Route</b> POSTs the fully parsed
 * message here as {@code application/x-www-form-urlencoded} (or multipart form).
 * Public route (under {@code /api/inbound/**}), guarded by Mailgun's own HMAC
 * signature ({@link MailgunSignatureVerifier}) rather than our {@code X-Mayo-Signature}.
 *
 * <p><b>Accepted form fields</b> (Mailgun's standard inbound-parse fields):
 * <ul>
 *   <li>{@code recipient} — the ingest address the mail was sent to (== vendor {@code ingestAddress}).</li>
 *   <li>{@code sender} / {@code from} — the original sender (aggregator).</li>
 *   <li>{@code subject}</li>
 *   <li>{@code body-plain} → {@code stripped-text} → {@code body-html} (first non-blank wins;
 *       html is stripped to text).</li>
 *   <li>{@code Message-Id}</li>
 *   <li>{@code timestamp}, {@code token}, {@code signature} — Mailgun's signature triplet.</li>
 * </ul>
 *
 * <p><b>Status codes</b>:
 * <ul>
 *   <li><b>503</b> — signing key unset (feature disabled).</li>
 *   <li><b>401</b> — bad/missing signature, stale timestamp (&gt;15 min), or token replay.</li>
 *   <li><b>200</b> — signature ok AND recipient matched → processed
 *       ({@code {status:accepted,newOrders}}).</li>
 *   <li><b>200</b> — signature ok but recipient unknown → {@code {status:ignored}} (no body
 *       detail, so we don't leak which addresses exist; Mailgun won't retry a 200).</li>
 *   <li><b>406</b> — permanently unprocessable (no recipient at all) so Mailgun STOPS retrying.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/inbound")
public class InboundMailgunController {

    private static final Logger log = LoggerFactory.getLogger(InboundMailgunController.class);

    private final MailgunSignatureVerifier verifier;
    private final VendorRepository vendorRepo;
    private final IngestionCore ingestionCore;
    private final AppMetrics metrics;
    private final AuditService auditService;

    public InboundMailgunController(MailgunSignatureVerifier verifier,
                                    VendorRepository vendorRepo,
                                    IngestionCore ingestionCore,
                                    AppMetrics metrics,
                                    AuditService auditService) {
        this.verifier = verifier;
        this.vendorRepo = vendorRepo;
        this.ingestionCore = ingestionCore;
        this.metrics = metrics;
        this.auditService = auditService;
    }

    @PostMapping("/mailgun")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestParam Map<String, String> form) {

        // 1) Feature gate + signature.
        MailgunSignatureVerifier.Result sig = verifier.verify(
                form.get("timestamp"), form.get("token"), form.get("signature"));
        switch (sig) {
            case NOT_CONFIGURED -> {
                // Feature disabled — tell the operator, don't pretend to accept.
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "disabled"));
            }
            case INVALID, STALE_OR_REPLAY -> {
                metrics.webhookInbound("mailgun_rejected_signature");
                auditService.record(null, null, "inbound.reject", "webhook", null,
                        Map.of("provider", "mailgun", "reason",
                                sig == MailgunSignatureVerifier.Result.STALE_OR_REPLAY
                                        ? "stale_or_replay" : "bad_signature"));
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "rejected"));
            }
            default -> { /* VALID — fall through */ }
        }

        // 2) Pull fields.
        String recipient = firstNonBlank(form, "recipient", "to");
        String from = firstNonBlank(form, "sender", "from");
        String subject = firstNonBlank(form, "subject", "Subject");
        String body = resolveBody(form);
        String messageId = firstNonBlank(form, "Message-Id", "message-id", "Message-ID");

        if (recipient == null) {
            // No recipient at all → permanently unprocessable. 406 tells Mailgun to STOP retrying.
            metrics.webhookInbound("mailgun_unprocessable");
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                    .body(Map.of("status", "unprocessable"));
        }

        Vendor vendor = vendorRepo.findByIngestAddress(recipient.trim().toLowerCase()).orElse(null);
        if (vendor == null) {
            // Unknown recipient — accept quietly (200, no detail) so we don't leak which
            // ingest addresses exist. 200 also stops Mailgun retrying. Nothing is stored.
            metrics.webhookInbound("mailgun_unmatched");
            auditService.record(null, null, "inbound.reject", "webhook", null,
                    Map.of("provider", "mailgun", "reason", "unmatched_recipient"));
            log.info("Mailgun inbound for unmatched recipient — ignored");
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        RawMessage msg = new RawMessage(from, subject, body, messageId);
        IngestResult result = ingestionCore.process(
                vendor.getAccountId(), vendor.getId(), MailSourceType.FORWARDING, msg);

        metrics.webhookInbound("mailgun_accepted");
        auditService.record(vendor.getAccountId(), null, "inbound.accept", "vendor",
                String.valueOf(vendor.getId()),
                Map.of("provider", "mailgun", "newOrders", result.newOrders()));
        return ResponseEntity.ok(Map.of("status", "accepted", "newOrders", result.newOrders()));
    }

    /**
     * body-plain → stripped-text → html-stripped body-html. The plain/stripped MIME parts
     * carry ₹ as literal HTML entities (&#x20b9; / &#8377;), so they MUST be decoded too —
     * otherwise an amount regex reads "20"/"8377" out of the entity. (stripHtml already
     * decodes internally, so the html branch needs no extra call.)
     */
    private static String resolveBody(Map<String, String> form) {
        String plain = firstNonBlank(form, "body-plain", "bodyPlain");
        if (plain != null) {
            return MimeEmailParser.decodeEntities(plain);
        }
        String stripped = firstNonBlank(form, "stripped-text", "strippedText");
        if (stripped != null) {
            return MimeEmailParser.decodeEntities(stripped);
        }
        String html = firstNonBlank(form, "body-html", "bodyHtml");
        return html != null ? MimeEmailParser.stripHtml(html) : null;
    }

    private static String firstNonBlank(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

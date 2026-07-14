package com.mayoclone.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.MimeEmailParser;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.ingest.inbound.InboundSignatureVerifier;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * PRIMARY ingestion path: an inbound-email provider POSTs a forwarded aggregator
 * email here. Public route (permitted in SecurityConfig) but guarded by an HMAC
 * signature ({@link InboundSignatureVerifier}).
 *
 * <p><b>Expected JSON body</b> (alternate Mailgun-style keys tolerated):
 * <pre>
 * {
 *   "recipient":  "&lt;token&gt;@inbound.mayoclone.app",   // or "to"
 *   "from":       "orders@zoopindia.com",                  // or "sender"
 *   "subject":    "Order confirmed",
 *   "bodyPlain":  "....",                                  // or "body-plain"
 *   "bodyHtml":   "&lt;html&gt;...",                       // or "body-html"
 *   "messageId":  "&lt;abc@zoopindia.com&gt;"              // or "Message-Id"
 * }
 * </pre>
 *
 * <p>Behaviour: 401 on bad/missing/stale signature; 200 when the recipient maps to
 * a FORWARDING vendor and the message is processed (idempotent on messageId);
 * 202 when the recipient matches no vendor (we do NOT reveal which addresses
 * exist). Emits {@code mayoclone.webhook.inbound} + an audit event.
 */
@RestController
@RequestMapping("/api/inbound")
public class InboundEmailController {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailController.class);

    private final InboundSignatureVerifier verifier;
    private final VendorRepository vendorRepo;
    private final IngestionCore ingestionCore;
    private final AppMetrics metrics;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InboundEmailController(InboundSignatureVerifier verifier,
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

    @PostMapping("/email")
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws IOException {
        String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
        String signature = request.getHeader(InboundSignatureVerifier.HEADER);

        if (!verifier.verify(signature, rawBody)) {
            metrics.webhookInbound("rejected_signature");
            auditService.record(null, null, "inbound.reject", "webhook", null,
                    Map.of("reason", "bad_signature"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "rejected"));
        }

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(rawBody.isBlank() ? "{}" : rawBody, Map.class);
        } catch (Exception e) {
            metrics.webhookInbound("rejected_payload");
            return ResponseEntity.badRequest().body(Map.of("status", "invalid_payload"));
        }

        String recipient = firstNonBlank(payload, "recipient", "to");
        String from = firstNonBlank(payload, "from", "sender");
        String subject = firstNonBlank(payload, "subject", "Subject");
        // Prefer plain text (decode ₹/HTML entities); else strip+decode the HTML part.
        // Never pass a raw body: undecoded entities corrupt amounts (₹180→₹20) and raw
        // HTML tags wreck every field regex.
        String plain = firstNonBlank(payload, "bodyPlain", "body-plain", "text");
        String html = firstNonBlank(payload, "bodyHtml", "body-html");
        String body = plain != null ? MimeEmailParser.decodeEntities(plain)
                : (html != null ? MimeEmailParser.stripHtml(html) : null);
        String messageId = firstNonBlank(payload, "messageId", "Message-Id", "message-id");

        Vendor vendor = recipient == null ? null
                : vendorRepo.findByIngestAddress(recipient.trim().toLowerCase()).orElse(null);

        if (vendor == null) {
            // Unknown recipient — accept quietly (202) so we don't leak which
            // ingest addresses exist. Nothing is stored.
            metrics.webhookInbound("unmatched");
            auditService.record(null, null, "inbound.reject", "webhook", null,
                    Map.of("reason", "unmatched_recipient"));
            log.info("Inbound email for unmatched recipient — ignored");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "ignored"));
        }

        RawMessage msg = new RawMessage(from, subject, body, messageId);
        IngestResult result = ingestionCore.process(
                vendor.getAccountId(), vendor.getId(), MailSourceType.FORWARDING, msg);

        metrics.webhookInbound("accepted");
        auditService.record(vendor.getAccountId(), null, "inbound.accept", "vendor",
                String.valueOf(vendor.getId()), Map.of("newOrders", result.newOrders()));
        return ResponseEntity.ok(Map.of("status", "accepted", "newOrders", result.newOrders()));
    }

    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }
}

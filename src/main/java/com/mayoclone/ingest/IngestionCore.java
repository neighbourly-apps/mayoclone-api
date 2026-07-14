package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.IngestFailureReason;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.enrich.EnrichmentProperties;
import com.mayoclone.jobs.DashboardEnrichJobHandler;
import com.mayoclone.jobs.JobQueue;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.parser.IrctcEmailParser;
import com.mayoclone.parser.ParsedOrder;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.service.AggregatorService;
import com.mayoclone.service.OrderCommandService;
import com.mayoclone.service.TrainNameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single source-agnostic pipeline: route → parse → dedup → save for one
 * {@link RawMessage}. On a route/parse failure the message is written to the
 * review queue ({@code ingest_failure}) instead of being dropped, and a metric is
 * emitted. Used by IMAP, Gmail, the inbound webhook, and the demo endpoint.
 */
@Service
public class IngestionCore {

    private static final Logger log = LoggerFactory.getLogger(IngestionCore.class);
    private static final int SNIPPET_MAX = 2000;

    /** Heuristic: an email body that clearly signals cash / COD payment. */
    private static final Pattern COD_HINT = Pattern.compile(
            "cash\\s*on\\s*delivery|pay\\s*on\\s*delivery|pay\\s*at\\s*delivery|cash\\s*payment|\\bCOD\\b",
            Pattern.CASE_INSENSITIVE);

    // Post-order NOTICES (not orders): IRCTC "Delivery Time Passed", provisional-invoice
    // and feedback mails carry no item table and must never become empty orders.
    private static final Pattern NON_ORDER_NOTICE = Pattern.compile(
            "delivery\\s*time\\s*(has\\s*)?passed|provisional\\s*invoice"
                    + "|feedback\\s*for\\s*(this|your)\\s*order|thank\\s*you\\s*for\\s*ordering",
            Pattern.CASE_INSENSITIVE);

    /**
     * True for a post-order notification (no item table AND no positive amount AND a notice
     * phrase in the subject/body). Requiring "no order content" guarantees a real order —
     * which always has items or an amount — is never skipped.
     */
    private static boolean isNonOrderNotification(RawMessage msg, ParsedOrder p) {
        boolean noOrderContent = (p.items() == null || p.items().isEmpty())
                && (p.amount() == null || p.amount().signum() <= 0);
        if (!noOrderContent) {
            return false;
        }
        String hay = (msg.subject() == null ? "" : msg.subject()) + "\n"
                + (msg.body() == null ? "" : msg.body());
        return NON_ORDER_NOTICE.matcher(hay).find();
    }

    private final List<IrctcEmailParser> parsers;
    private final IrctcOrderRepository orderRepo;
    private final AggregatorService aggregatorService;
    private final IngestFailureRepository failureRepo;
    private final AppMetrics metrics;
    private final OrderCommandService orderCommandService;
    private final JobQueue jobQueue;
    private final EnrichmentProperties enrichmentProperties;
    private final TrainNameService trainNameService;

    public IngestionCore(List<IrctcEmailParser> parsers,
                         IrctcOrderRepository orderRepo,
                         AggregatorService aggregatorService,
                         IngestFailureRepository failureRepo,
                         AppMetrics metrics,
                         OrderCommandService orderCommandService,
                         JobQueue jobQueue,
                         EnrichmentProperties enrichmentProperties,
                         TrainNameService trainNameService) {
        this.parsers = parsers;
        this.orderRepo = orderRepo;
        this.aggregatorService = aggregatorService;
        this.failureRepo = failureRepo;
        this.metrics = metrics;
        this.orderCommandService = orderCommandService;
        this.jobQueue = jobQueue;
        this.enrichmentProperties = enrichmentProperties;
        this.trainNameService = trainNameService;
    }

    /**
     * Process one raw message end-to-end. Returns fetched=1 always; newOrders=1 if
     * a fresh order was stored (else 0). {@code accountId} is the owning tenant
     * (nullable only for an unmatched inbound recipient); {@code vendorId} may be null.
     */
    public IngestResult process(Long accountId, Long vendorId, MailSourceType sourceType, RawMessage msg) {
        // 1. Route the email to an aggregator by sender domain.
        Optional<Aggregator> agg = aggregatorService.findBySender(msg.from());
        if (agg.isEmpty()) {
            recordFailure(accountId, vendorId, sourceType, msg, IngestFailureReason.NO_AGGREGATOR_MATCH);
            return new IngestResult(1, 0);
        }

        // 2. Pick the first parser that supports this aggregator.
        Optional<IrctcEmailParser> parser = parsers.stream()
                .filter(p -> p.supports(agg.get(), msg.from(), msg.subject()))
                .findFirst();
        if (parser.isEmpty()) {
            recordFailure(accountId, vendorId, sourceType, msg, IngestFailureReason.NO_PARSER);
            return new IngestResult(1, 0);
        }

        ParsedOrder parsed;
        try {
            parsed = parser.get().parse(agg.get(), msg.from(), msg.subject(), msg.body(), msg.messageId());
        } catch (RuntimeException e) {
            log.warn("Parser {} threw for aggregator {}: {}",
                    parser.get().getClass().getSimpleName(), agg.get().getCode(), e.getMessage());
            recordFailure(accountId, vendorId, sourceType, msg, IngestFailureReason.PARSE_FAILED);
            return new IngestResult(1, 0);
        }

        // Not an order: aggregators also send post-order NOTICES (e.g. IRCTC "Delivery
        // Time Passed", provisional-invoice / feedback mails) that carry no item table.
        // These must NOT become empty orders — skip them silently (fetched, 0 new).
        if (isNonOrderNotification(msg, parsed)) {
            log.debug("Skipping non-order notification from {} (subject '{}')",
                    msg.from(), msg.subject());
            return new IngestResult(1, 0);
        }

        // Dedup 1: same source email already ingested (idempotency across re-sync/retry).
        if (parsed.sourceMessageId() != null
                && orderRepo.existsBySourceMessageId(parsed.sourceMessageId())) {
            return new IngestResult(1, 0);
        }
        // Dedup 2: same (aggregator, externalOrderId) already stored.
        if (orderRepo.existsByAggregatorAndExternalOrderId(agg.get(), parsed.externalOrderId())) {
            return new IngestResult(1, 0);
        }

        IrctcOrder entity = toEntity(parsed, agg.get(), accountId, vendorId, msg.body());
        // Flag (never hide) a low-confidence parse. The order is ALWAYS saved.
        applyReviewVerdict(entity, agg.get());
        IrctcOrder saved = orderRepo.save(entity);
        metrics.orderIngested(agg.get().getCode(), sourceType == null ? null : sourceType.name());
        // Record the initial NEW status event + push a realtime NEW_ORDER event.
        orderCommandService.recordCreated(saved);
        // Dashboard order-enrichment (OFF by default). When enabled, and the order has a
        // known vendor but no real passenger name (the email omitted it), enqueue a
        // durable job to look the name up on the vendor dashboard. Coalesced per order id
        // so a re-sync never piles up duplicates. When disabled this block is inert and
        // behavior is byte-for-byte the current pipeline.
        maybeEnqueueEnrichment(saved);
        return new IngestResult(1, 1);
    }

    /**
     * Gated enrichment enqueue. No-op unless {@code mayoclone.enrich.enabled=true}, the
     * order carries a vendor id, and the passenger name is missing/"Unknown".
     */
    private void maybeEnqueueEnrichment(IrctcOrder saved) {
        if (!enrichmentProperties.isEnabled()
                || saved.getVendorId() == null
                || !DashboardEnrichJobHandler.isUnknownOrBlank(saved.getPassengerName())) {
            return;
        }
        jobQueue.enqueue(DashboardEnrichJobHandler.JOB_TYPE,
                saved.getAccountId(), saved.getVendorId(),
                "{\"orderId\":" + saved.getId() + "}",
                "enrich:" + saved.getId());
    }

    private void recordFailure(Long accountId, Long vendorId, MailSourceType sourceType,
                               RawMessage msg, IngestFailureReason reason) {
        log.debug("Ingest failure {} for sender '{}'", reason, msg.from());
        IngestFailure f = new IngestFailure();
        f.setAccountId(accountId);
        f.setVendorId(vendorId);
        f.setFromAddress(msg.from());
        f.setSubject(truncate(msg.subject(), 1000));
        f.setReason(reason);
        f.setRawSnippet(truncate(msg.body(), SNIPPET_MAX)); // truncated snippet for list views
        f.setRawBody(msg.body()); // FULL body: the faithful replay source (see IngestFailureService.retry)
        f.setSourceType(sourceType);
        f.setMessageId(truncate(msg.messageId(), 512));
        f.setCreatedAt(Instant.now());
        failureRepo.save(f);
        metrics.ingestFailure(reason.name());
    }

    private IrctcOrder toEntity(ParsedOrder p, Aggregator aggregator, Long accountId, Long vendorId, String body) {
        IrctcOrder o = new IrctcOrder();
        o.setAggregator(aggregator);
        o.setAccountId(accountId);
        o.setVendorId(vendorId);
        // Global order-id guard: a blank or non-order-looking token (a bare word like
        // "for"/"Booking", or something too short to be a real id) is replaced by a
        // STABLE generated id <AGGCODE>-<hash-of-sourceMessageId>. That synthesized
        // shape is later caught by applyReviewVerdict → flagged "order id not found
        // (generated)". A real numeric/alphanumeric id passes through unchanged.
        o.setExternalOrderId(isRealOrderId(p.externalOrderId())
                ? p.externalOrderId().trim()
                : generatedOrderId(aggregator.getCode(), p.sourceMessageId()));
        o.setPnr(p.pnr());
        // Global train-number guard: keep ONLY digits and store it ONLY when the result
        // is a plausible train number (4–5 digits) — NEVER text. A captured train NAME
        // (e.g. "EXP 123") is dropped from the number and preserved in trainName instead.
        String cleanedTrainNumber = plausibleTrainNumber(p.trainNumber());
        o.setTrainNumber(cleanedTrainNumber);
        // Self-learning catalog: an email that carried the name teaches it; an email
        // that had only the number (e.g. RailRecipe) borrows a previously-learned name.
        String trainName = resolveTrainName(p.trainName(), p.trainNumber(), cleanedTrainNumber);
        if (cleanedTrainNumber != null) {
            try {
                if (!isBlank(trainName)) {
                    trainNameService.record(cleanedTrainNumber, trainName);
                } else {
                    trainName = trainNameService.resolve(cleanedTrainNumber).orElse(trainName);
                }
            } catch (RuntimeException ex) {
                // The catalog is a convenience — never let it block an order from being saved.
                log.debug("train-name catalog lookup/record skipped: {}", ex.toString());
            }
        }
        o.setTrainName(trainName);
        o.setCoach(p.coach());
        o.setBerth(p.berth());
        o.setBoardingStationCode(p.boardingStationCode());
        o.setDeliveryStationCode(p.deliveryStationCode());
        o.setDeliveryStationName(p.deliveryStationName());
        o.setPassengerName(p.passengerName() != null ? p.passengerName() : "Unknown");
        o.setPassengerPhone(p.passengerPhone());
        // An order with no parseable delivery date must still be actionable "today"
        // instead of vanishing from the Daily Business board / reports / settlement,
        // which all window on deliveryDate. Fall back to the arrival day.
        o.setDeliveryDate(p.deliveryDate() != null ? p.deliveryDate() : LocalDate.now());
        o.setDeliverySlot(p.deliverySlot());
        o.setAmount(p.amount());
        o.setCurrency(p.currency() != null ? p.currency() : "INR");
        o.setAmountToCollect(p.amountToCollect());
        o.setSubtotalAmount(p.subtotalAmount());
        o.setGstAmount(p.gstAmount());
        o.setDeliveryFee(p.deliveryFee());
        o.setDiscountAmount(p.discountAmount());
        // Ingested orders are ONLINE; a fresh order enters the lifecycle at NEW.
        o.setOrderType(OrderType.ONLINE);
        // Prefer the payment mode the parser extracted from the email; fall back to
        // the body-text heuristic only when the parser found no explicit signal.
        o.setPaymentMode(resolvePaymentMode(p.paymentMode(), body));
        o.setStatus(OrderStatus.NEW);
        o.setSubject(p.subject());
        o.setSourceMessageId(p.sourceMessageId());
        o.setItems(p.items());
        // Retain the FULL raw email untruncated — the ultimate safety net so the
        // source is always recoverable and re-parseable even if a field parsed wrong.
        o.setRawEmail(body);
        o.setPlacedAt(Instant.now());
        o.setCreatedAt(Instant.now());
        return o;
    }

    /**
     * Compute a low-confidence verdict over an already-built order and stamp
     * {@code needsReview}/{@code reviewReason} on it. NEVER drops the order — a
     * flagged order is still saved so an operator can review it rather than losing
     * it behind a bad parse. Reasons are concatenated with "; ".
     */
    private void applyReviewVerdict(IrctcOrder o, Aggregator aggregator) {
        List<String> reasons = new ArrayList<>();
        if (o.getItems() == null || o.getItems().isEmpty()) {
            reasons.add("no items parsed");
        }
        if (o.getAmount() == null || o.getAmount().signum() <= 0) {
            reasons.add("amount missing/zero");
        }
        if (isBlank(o.getTrainNumber())) {
            reasons.add("train number missing");
        }
        // NOTE: a missing delivery station is NOT a review trigger — several real
        // formats (e.g. RailRestro) legitimately carry no station, so flagging on it
        // produced false positives. Items/amount/train/order-id remain the signals.
        if (looksGenerated(o.getExternalOrderId(), aggregator.getCode())) {
            reasons.add("order id not found (generated)");
        }
        if (!reasons.isEmpty()) {
            o.setNeedsReview(true);
            o.setReviewReason(String.join("; ", reasons));
        }
    }

    /**
     * The generic parser falls back to {@code <AGGCODE>-<digits>} (hashed messageId)
     * when it finds no real order id. Detect that synthesized shape so the order is
     * flagged for review rather than trusted.
     */
    private static boolean looksGenerated(String externalOrderId, String aggregatorCode) {
        if (externalOrderId == null || aggregatorCode == null || aggregatorCode.isBlank()) {
            return false;
        }
        return Pattern.compile("^" + Pattern.quote(aggregatorCode) + "-\\d+$").matcher(externalOrderId).matches();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * A real order id must be non-blank, at least 3 chars, and contain a digit. This
     * rejects a bare alphabetic dictionary word ("for", "Booking") and anything too
     * short to be a genuine id, while letting a numeric/alphanumeric id ("EXT-9",
     * "ZOOP-123456", "20250706") pass through untouched.
     */
    private static boolean isRealOrderId(String id) {
        if (id == null) {
            return false;
        }
        String t = id.trim();
        return t.length() >= 3 && t.chars().anyMatch(Character::isDigit);
    }

    /**
     * The stable fallback order id — mirrors the generic parser's own fallback shape
     * ({@code <AGGCODE>-<abs hash of the source message id>}) so it is deterministic
     * across re-syncs AND recognised by {@link #looksGenerated} for the review flag.
     */
    private static String generatedOrderId(String aggregatorCode, String sourceMessageId) {
        String code = (aggregatorCode == null || aggregatorCode.isBlank()) ? "GEN" : aggregatorCode;
        return code + "-" + Math.abs(String.valueOf(sourceMessageId).hashCode());
    }

    /**
     * Digits-only train number, kept ONLY when it is a plausible train number (4–5
     * digits). Anything else (a name, a partial, junk) becomes null — we NEVER store
     * text in the train-number field.
     */
    private static String plausibleTrainNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return (digits.length() == 4 || digits.length() == 5) ? digits : null;
    }

    /**
     * Keep an explicitly parsed train name. Otherwise, if the raw train-NUMBER field
     * actually held a name (it had letters and did not survive as a plausible number),
     * preserve that text as the train name rather than discarding it.
     */
    private static String resolveTrainName(String parsedTrainName, String rawTrainNumber, String cleanedTrainNumber) {
        if (!isBlank(parsedTrainName)) {
            return parsedTrainName;
        }
        if (cleanedTrainNumber == null && rawTrainNumber != null
                && rawTrainNumber.chars().anyMatch(Character::isLetter)) {
            return rawTrainNumber.trim();
        }
        return parsedTrainName;
    }

    /**
     * Map the parser's payment-mode string onto the persisted enum. "COD" → COD,
     * "PREPAID"/"PAID" (paid online) → PREPAID; when the parser found no signal,
     * fall back to the body-text heuristic.
     */
    private static PaymentMode resolvePaymentMode(String parsed, String body) {
        if ("COD".equalsIgnoreCase(parsed)) {
            return PaymentMode.COD;
        }
        if ("PREPAID".equalsIgnoreCase(parsed) || "PAID".equalsIgnoreCase(parsed)) {
            return PaymentMode.PREPAID;
        }
        return detectPaymentMode(body);
    }

    /** COD when the email body clearly indicates cash/COD payment, else PREPAID. */
    private static PaymentMode detectPaymentMode(String body) {
        return body != null && COD_HINT.matcher(body).find() ? PaymentMode.COD : PaymentMode.PREPAID;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

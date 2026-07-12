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
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.parser.IrctcEmailParser;
import com.mayoclone.parser.ParsedOrder;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.service.AggregatorService;
import com.mayoclone.service.OrderCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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

    private final List<IrctcEmailParser> parsers;
    private final IrctcOrderRepository orderRepo;
    private final AggregatorService aggregatorService;
    private final IngestFailureRepository failureRepo;
    private final AppMetrics metrics;
    private final OrderCommandService orderCommandService;

    public IngestionCore(List<IrctcEmailParser> parsers,
                         IrctcOrderRepository orderRepo,
                         AggregatorService aggregatorService,
                         IngestFailureRepository failureRepo,
                         AppMetrics metrics,
                         OrderCommandService orderCommandService) {
        this.parsers = parsers;
        this.orderRepo = orderRepo;
        this.aggregatorService = aggregatorService;
        this.failureRepo = failureRepo;
        this.metrics = metrics;
        this.orderCommandService = orderCommandService;
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

        // Dedup 1: same source email already ingested (idempotency across re-sync/retry).
        if (parsed.sourceMessageId() != null
                && orderRepo.existsBySourceMessageId(parsed.sourceMessageId())) {
            return new IngestResult(1, 0);
        }
        // Dedup 2: same (aggregator, externalOrderId) already stored.
        if (orderRepo.existsByAggregatorAndExternalOrderId(agg.get(), parsed.externalOrderId())) {
            return new IngestResult(1, 0);
        }

        IrctcOrder saved = orderRepo.save(toEntity(parsed, agg.get(), accountId, vendorId, msg.body()));
        metrics.orderIngested(agg.get().getCode(), sourceType == null ? null : sourceType.name());
        // Record the initial NEW status event + push a realtime NEW_ORDER event.
        orderCommandService.recordCreated(saved);
        return new IngestResult(1, 1);
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
        f.setRawSnippet(truncate(msg.body(), SNIPPET_MAX)); // truncated; no secrets in an email body
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
        o.setExternalOrderId(p.externalOrderId());
        o.setPnr(p.pnr());
        o.setTrainNumber(p.trainNumber());
        o.setTrainName(p.trainName());
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
        // Ingested orders are ONLINE; a fresh order enters the lifecycle at NEW.
        o.setOrderType(OrderType.ONLINE);
        // Prefer the payment mode the parser extracted from the email; fall back to
        // the body-text heuristic only when the parser found no explicit signal.
        o.setPaymentMode(resolvePaymentMode(p.paymentMode(), body));
        o.setStatus(OrderStatus.NEW);
        o.setSubject(p.subject());
        o.setSourceMessageId(p.sourceMessageId());
        o.setItems(p.items());
        o.setPlacedAt(Instant.now());
        o.setCreatedAt(Instant.now());
        return o;
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

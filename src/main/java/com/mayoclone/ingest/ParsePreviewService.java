package com.mayoclone.ingest;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.dto.ParsePreviewRequest;
import com.mayoclone.dto.ParsePreviewResponse;
import com.mayoclone.dto.ParsePreviewResponse.AggregatorRef;
import com.mayoclone.parser.IrctcEmailParser;
import com.mayoclone.parser.ParsedOrder;
import com.mayoclone.service.AggregatorService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs the SAME routing + parse as {@link IngestionCore}, but WITHOUT persisting —
 * the engine behind {@code POST /api/dev/parse-preview} and the {@code .eml} test
 * harness. Produces a {@link ParsePreviewResponse} with the matched aggregator, the
 * full parsed order, a {@code wouldPersist} flag, and human-readable warnings so an
 * operator can tune parsers against real emails.
 */
@Service
public class ParsePreviewService {

    private final List<IrctcEmailParser> parsers;
    private final AggregatorService aggregatorService;
    private final MimeEmailParser mimeEmailParser;

    public ParsePreviewService(List<IrctcEmailParser> parsers,
                               AggregatorService aggregatorService,
                               MimeEmailParser mimeEmailParser) {
        this.parsers = parsers;
        this.aggregatorService = aggregatorService;
        this.mimeEmailParser = mimeEmailParser;
    }

    /** Preview from a request that carries either discrete fields or a raw RFC822 email. */
    public ParsePreviewResponse preview(ParsePreviewRequest req) {
        String from = req.from();
        String subject = req.subject();
        String body = req.body();
        String messageId = null;

        if (req.raw() != null && !req.raw().isBlank()) {
            RawMessage m = mimeEmailParser.parse(req.raw());
            // Explicit discrete fields win over MIME-derived ones; else take from MIME.
            from = blankToNull(from) != null ? from : m.from();
            subject = blankToNull(subject) != null ? subject : m.subject();
            body = blankToNull(body) != null ? body : m.body();
            messageId = m.messageId();
        }
        return preview(from, subject, body, messageId);
    }

    /** Preview from already-derived fields (used by the .eml harness after MIME parsing). */
    public ParsePreviewResponse preview(String from, String subject, String body, String messageId) {
        List<String> warnings = new ArrayList<>();

        Optional<Aggregator> agg = aggregatorService.findBySender(from);
        if (agg.isEmpty()) {
            warnings.add("no aggregator matched sender" + (from == null ? " (from is null)" : " '" + from + "'"));
            return new ParsePreviewResponse(null, null, false, warnings);
        }
        Aggregator a = agg.get();
        AggregatorRef ref = new AggregatorRef(a.getCode(), a.getName());

        Optional<IrctcEmailParser> parser = parsers.stream()
                .filter(p -> p.supports(a, from, subject))
                .findFirst();
        if (parser.isEmpty()) {
            warnings.add("aggregator '" + a.getCode() + "' matched but no parser supports it");
            return new ParsePreviewResponse(ref, null, false, warnings);
        }

        ParsedOrder parsed;
        try {
            parsed = parser.get().parse(a, from, subject, body, messageId);
        } catch (RuntimeException e) {
            warnings.add("parser " + parser.get().getClass().getSimpleName()
                    + " threw: " + e.getMessage());
            return new ParsePreviewResponse(ref, null, false, warnings);
        }

        collectFieldWarnings(parsed, warnings);
        // Mirrors IngestionCore: an aggregator matched and a parser produced an order.
        return new ParsePreviewResponse(ref, parsed, true, warnings);
    }

    /** Human-readable notes about fields that were missing or will be defaulted. */
    private static void collectFieldWarnings(ParsedOrder p, List<String> warnings) {
        if (isBlank(p.pnr())) {
            warnings.add("PNR not parsed");
        }
        if (isBlank(p.trainNumber())) {
            warnings.add("train number not parsed");
        }
        if (isBlank(p.deliveryStationCode())) {
            warnings.add("delivery station code not parsed");
        }
        if (p.deliveryDate() == null) {
            warnings.add("deliveryDate not parsed — would default to today");
        }
        if (p.amount() == null || p.amount().compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("amount not found (or zero)");
        }
        if (p.items() == null || p.items().isEmpty()) {
            warnings.add("no line items parsed");
        }
        if (p.passengerName() == null || "Unknown".equals(p.passengerName())) {
            warnings.add("passenger name not parsed — would default to \"Unknown\"");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

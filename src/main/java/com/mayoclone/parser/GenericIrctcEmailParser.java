package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust, aggregator-agnostic parser for IRCTC e-catering order-confirmation
 * emails. It regex-extracts the IRCTC fields (PNR, train no. + name, coach/berth,
 * delivery station code+name, passenger name+phone, delivery date + slot, line
 * items, total) from typical order text and degrades gracefully — returning
 * sensible defaults rather than throwing — when a field is absent.
 *
 * <p>This handles ALL seeded aggregators so nothing is left unparsed. It is
 * registered last (lowest precedence) so an aggregator-tuned parser such as
 * {@link ZoopEmailParser} can win where it applies. Extraction helpers are
 * {@code protected} so subclasses can refine individual patterns.
 */
@Component
@Order(100)
public class GenericIrctcEmailParser implements IrctcEmailParser {

    // "Order ID: ZOOP1023", "Order No: 998", "#UE900"
    protected static final Pattern ORDER_ID = Pattern.compile(
            "(?:order\\s*(?:id|no\\.?|number)\\s*[:#-]?\\s*|#)([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);

    // "PNR: 4512367890"
    protected static final Pattern PNR = Pattern.compile(
            "PNR\\s*(?:No\\.?|Number)?\\s*[:#-]?\\s*(\\d{10})", Pattern.CASE_INSENSITIVE);

    // "Train No.: 12951 Mumbai Rajdhani Express"
    protected static final Pattern TRAIN = Pattern.compile(
            "Train\\s*(?:No\\.?|Number)?\\.?\\s*[:#-]?\\s*(\\d{5})\\s*[-–:]?\\s*([^\\r\\n]*)",
            Pattern.CASE_INSENSITIVE);

    // "Coach: B3"
    protected static final Pattern COACH = Pattern.compile(
            "Coach\\s*(?:No\\.?)?\\s*[:#-]?\\s*([A-Za-z]{0,2}\\d{1,2}[A-Za-z]?)",
            Pattern.CASE_INSENSITIVE);

    // "Berth: 32" / "Berth No: 32 (Side Upper)"
    protected static final Pattern BERTH = Pattern.compile(
            "Berth\\s*(?:No\\.?)?\\s*[:#-]?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    // "Boarding: BCT" / "Boarding Station: BCT"
    protected static final Pattern BOARDING = Pattern.compile(
            "Boarding\\s*(?:Station)?\\s*(?:Code)?\\s*[:#-]?\\s*([A-Z]{2,5})\\b",
            Pattern.CASE_INSENSITIVE);

    // "Delivery Station: NDLS New Delhi"
    protected static final Pattern DELIVERY_STATION = Pattern.compile(
            "Delivery\\s*Station\\s*(?:Code)?\\s*[:#-]?\\s*([A-Z]{2,5})\\b\\s*([^\\r\\n]*)",
            Pattern.CASE_INSENSITIVE);

    // "Passenger: Rajesh Kumar" / "Passenger Name - Rajesh Kumar"
    protected static final Pattern PASSENGER = Pattern.compile(
            "Passenger\\s*(?:Name)?\\s*[:#-]\\s*([A-Za-z][A-Za-z .]+?)\\s*(?:\\r?\\n|Phone|Mobile|$)",
            Pattern.CASE_INSENSITIVE);

    // "Phone: 9876543210" / "Mobile: ..." / "Contact: ..."
    protected static final Pattern PHONE = Pattern.compile(
            "(?:Phone|Mobile|Contact)\\s*(?:No\\.?)?\\s*[:#-]?\\s*(\\d{10})",
            Pattern.CASE_INSENSITIVE);

    // "Delivery Date: 2026-07-08" / "08/07/2026" / "08 Jul 2026"
    protected static final Pattern DELIVERY_DATE = Pattern.compile(
            "Delivery\\s*Date\\s*[:#-]?\\s*"
                    + "(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}|\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    // "Delivery Time: 13:00-13:30" / "Delivery Slot: 13:00 - 13:30" / "13:00"
    protected static final Pattern DELIVERY_SLOT = Pattern.compile(
            "Delivery\\s*(?:Time|Slot)\\s*[:#-]?\\s*"
                    + "(\\d{1,2}:\\d{2}(?:\\s*[-–]\\s*\\d{1,2}:\\d{2})?)",
            Pattern.CASE_INSENSITIVE);

    // "Total: ₹450", "Rs. 320", "INR 450"
    protected static final Pattern AMOUNT = Pattern.compile(
            "(?:grand\\s*total|total|amount|payable)?\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    // "2 x Veg Biryani - ₹180" / "1 X Filter Coffee – Rs. 40"
    protected static final Pattern LINE_ITEM = Pattern.compile(
            "(\\d+)\\s*[xX*]\\s*(.+?)\\s*[-–:]\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    };

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        // The generic parser handles any routed aggregator, so nothing is unparsed.
        return agg != null;
    }

    @Override
    public ParsedOrder parse(Aggregator agg, String from, String subject, String body, String messageId) {
        String safeBody = body == null ? "" : body;
        String fallbackId = agg.getCode() + "-" + Math.abs(String.valueOf(messageId).hashCode());

        return new ParsedOrder(
                agg.getCode(),
                extractOrderId(safeBody, fallbackId),
                extractGroup(PNR, safeBody, null),
                extractGroup(TRAIN, safeBody, null),
                extractTrainName(safeBody),
                extractGroup(COACH, safeBody, null),
                extractGroup(BERTH, safeBody, null),
                extractGroup(BOARDING, safeBody, null),
                extractGroup(DELIVERY_STATION, safeBody, null),
                extractDeliveryStationName(safeBody),
                extractPassenger(safeBody),
                extractGroup(PHONE, safeBody, null),
                extractDeliveryDate(safeBody),
                extractGroup(DELIVERY_SLOT, safeBody, null),
                extractAmount(safeBody),
                "INR",
                "CONFIRMED",
                extractItems(safeBody),
                subject,
                messageId
        );
    }

    protected String extractOrderId(String body, String fallback) {
        Matcher m = ORDER_ID.matcher(body);
        return m.find() ? m.group(1) : fallback;
    }

    /** Generic single-group extraction; trims and returns {@code fallback} when absent. */
    protected String extractGroup(Pattern pattern, String body, String fallback) {
        Matcher m = pattern.matcher(body);
        if (m.find()) {
            String v = m.group(1);
            return v == null ? fallback : v.trim();
        }
        return fallback;
    }

    /** The train name is group 2 of the {@link #TRAIN} pattern. */
    protected String extractTrainName(String body) {
        Matcher m = TRAIN.matcher(body);
        if (m.find() && m.group(2) != null) {
            String name = m.group(2).trim();
            return name.isBlank() ? null : name;
        }
        return null;
    }

    /** The delivery station name is group 2 of the {@link #DELIVERY_STATION} pattern. */
    protected String extractDeliveryStationName(String body) {
        Matcher m = DELIVERY_STATION.matcher(body);
        if (m.find() && m.group(2) != null) {
            String name = m.group(2).trim();
            return name.isBlank() ? null : name;
        }
        return null;
    }

    protected String extractPassenger(String body) {
        Matcher m = PASSENGER.matcher(body);
        return m.find() ? m.group(1).trim() : "Unknown";
    }

    protected LocalDate extractDeliveryDate(String body) {
        Matcher m = DELIVERY_DATE.matcher(body);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1).trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        return null;
    }

    protected BigDecimal extractAmount(String body) {
        // Prefer a "total"/"amount"-qualified match; else fall back to the largest currency value.
        Matcher m = AMOUNT.matcher(body);
        BigDecimal best = null;
        while (m.find()) {
            BigDecimal val = parseMoney(m.group(1));
            if (best == null || val.compareTo(best) > 0) {
                best = val;
            }
        }
        return best != null ? best : BigDecimal.ZERO;
    }

    protected List<OrderItem> extractItems(String body) {
        List<OrderItem> items = new ArrayList<>();
        Matcher m = LINE_ITEM.matcher(body);
        while (m.find()) {
            int qty = Integer.parseInt(m.group(1));
            String name = m.group(2).trim();
            BigDecimal price = parseMoney(m.group(3));
            items.add(new OrderItem(name, qty, price));
        }
        return items;
    }

    protected BigDecimal parseMoney(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }
}

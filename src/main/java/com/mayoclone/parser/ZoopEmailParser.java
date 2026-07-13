package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for Zoop (code {@code ZOOP}). It reuses the whole
 * {@link GenericIrctcEmailParser} extraction pipeline (which already handles
 * Zoop's name-first train "Mahakal Sup Exp/ 20416", vertical customer/train/coach
 * fields and COD/Prepaid detection) and refines the handful of things that are
 * Zoop-specific in the real "Order Confirmation" email:
 * <ul>
 *   <li>the order id, labeled "ZOOP Txn. No." (also legacy "Booking ID"/"Order Ref");</li>
 *   <li>the delivery station, given on an "At : &lt;name&gt;/ &lt;code&gt;" line;</li>
 *   <li>the line items, whose price/qty/amount columns arrive as BARE integers each
 *       on their own line under an "Item Details:" header — a shape the generic
 *       vertical/columnar parsers miss (they require a currency symbol or a decimal
 *       to recognise a value cell); and</li>
 *   <li>the bill breakdown, whose GST/Delivery/Discount lines are wrapped in a
 *       "(+)"/"(-)" sign prefix that defeats the generic start-of-line label match.</li>
 * </ul>
 * Registered ahead of the generic parser so it wins for ZOOP mail.
 */
@Component
@Order(10)
public class ZoopEmailParser extends GenericIrctcEmailParser {

    // "ZOOP Txn. No. : ZO119..." and legacy "Booking ID: ZOOP1023" / "Order Ref: ZP-77".
    private static final Pattern ZOOP_ORDER_ID = Pattern.compile(
            "(?:zoop\\s*txn\\.?\\s*no\\.?|booking\\s*id|order\\s*ref(?:erence)?)"
                    + "\\s*[:#-]?\\s*([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);

    // "At   : Virangana Lakshmibai Jhansi Junction/ VGLJ" (name/ code).
    private static final Pattern ZOOP_STATION = Pattern.compile(
            "(?m)^\\s*At\\b\\s*[:#-]?\\s*([^\\r\\n\\t]+?)(?=\\t| {2,}|\\u00a0{2,}|\\r|\\n|$)",
            Pattern.CASE_INSENSITIVE);

    // The "Item Details:" header that opens Zoop's vertical item block.
    private static final Pattern ITEM_DETAILS_HEADER = Pattern.compile(
            "^item\\s*details\\b.*", Pattern.CASE_INSENSITIVE);
    // A value line that is purely a number — Zoop's Price/Quantity/Amount columns are
    // bare integers ("230", "1"), each on its own line, optionally ₹/Rs/INR-prefixed.
    private static final Pattern NUMBER_LINE = Pattern.compile(
            "^(?:₹|Rs\\.?|INR)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    // Bill/summary labels that close the item block ("Base Price Total", "GST", …).
    private static final Pattern ITEM_STOP = Pattern.compile(
            "total|gst|discount|delivery|charge|fees?|payable|balance|paid|net\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "ZOOP".equalsIgnoreCase(agg.getCode());
    }

    @Override
    protected String extractOrderId(String body, String fallback) {
        Matcher m = ZOOP_ORDER_ID.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return super.extractOrderId(body, fallback);
    }

    @Override
    protected String[] extractStation(String body) {
        Matcher m = ZOOP_STATION.matcher(body);
        if (m.find()) {
            return parseStation(m.group(1).trim());
        }
        return super.extractStation(body);
    }

    // ---- line items ---------------------------------------------------------

    /**
     * Parse Zoop's vertical item block:
     * <pre>
     *   Item Details:
     *   Item Name   Price   Quantity   Amount   ← header labels, one per line
     *   Veg Biryani                             ← name
     *   230                                     ← unit price  (bare int)
     *   1                                       ← quantity    (bare int)
     *   230                                     ← line amount (bare int)
     *   ...
     *   Base Price Total                        ← bill block begins → stop
     * </pre>
     * An item is a name line (has a letter, not a bare number, not a field value)
     * IMMEDIATELY followed by one or more bare-number lines: the columns are
     * Price, Quantity, Amount, so the unit price is the first number and the qty
     * the middle one. Header labels are skipped naturally (they are not followed by
     * a number line). Returns empty — and defers to the generic parser — for the
     * synthetic/inline layouts (no "Item Details:" block), so nothing regresses.
     */
    @Override
    protected List<OrderItem> extractItems(String body) {
        List<OrderItem> items = extractZoopItems(body);
        return items.isEmpty() ? super.extractItems(body) : items;
    }

    private List<OrderItem> extractZoopItems(String body) {
        List<String> lines = nonEmptyLines(body);
        int n = lines.size();
        // Anchor to the "Item Details:" header so field lines above it (which carry
        // stop-words like "Delivery Station") never end the scan prematurely.
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (ITEM_DETAILS_HEADER.matcher(lines.get(i)).matches()) {
                start = i + 1;
                break;
            }
        }
        List<OrderItem> items = new ArrayList<>();
        if (start < 0) {
            return items; // no Zoop item block → let the generic parser try.
        }
        int i = start;
        while (i < n) {
            String line = lines.get(i);
            if (ITEM_STOP.matcher(line).find()) {
                break; // reached the bill breakdown.
            }
            boolean nameLine = HAS_LETTER.matcher(line).find()
                    && !line.startsWith(":")
                    && !NUMBER_LINE.matcher(line).matches();
            if (nameLine && i + 1 < n && NUMBER_LINE.matcher(lines.get(i + 1)).matches()) {
                List<BigDecimal> nums = new ArrayList<>();
                int j = i + 1;
                while (j < n) {
                    Matcher num = NUMBER_LINE.matcher(lines.get(j));
                    if (!num.matches()) {
                        break;
                    }
                    nums.add(parseMoney(num.group(1)));
                    j++;
                }
                BigDecimal unit = nums.get(0);
                int qty = nums.size() >= 3 ? Math.max(1, nums.get(1).intValue()) : 1;
                items.add(new OrderItem(line, qty, unit));
                i = j;
            } else {
                i++;
            }
        }
        return items;
    }

    private static List<String> nonEmptyLines(String body) {
        List<String> out = new ArrayList<>();
        for (String l : body.split("\\r?\\n")) {
            String s = l.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ---- bill breakdown -----------------------------------------------------

    /**
     * A Zoop bill line: an optional "(+)"/"(-)" sign wrapper, the {@code labelRegex},
     * then its "₹ &lt;amount&gt;" value on the SAME or the NEXT line. Anchored at line
     * start so "(+) GST on Delivery Charge" cannot satisfy a "delivery charge" query.
     */
    private static Pattern billLine(String labelRegex) {
        return Pattern.compile(
                "(?im)^[ \\t]*(?:\\([+\\-]\\)[ \\t]*)?(?:" + labelRegex + ")\\b"
                        + "[^\\r\\n0-9]*(?:\\r?\\n)?[ \\t]*(?:₹|Rs\\.?|INR)?[ \\t]*"
                        + "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    }

    /** Total GST = sum of every GST line (Zoop bills food GST and delivery GST separately). */
    @Override
    protected BigDecimal extractGst(String body) {
        Matcher m = billLine("gst").matcher(body);
        BigDecimal sum = null;
        while (m.find()) {
            BigDecimal v = parseMoney(m.group(1));
            sum = sum == null ? v : sum.add(v);
        }
        return sum != null ? sum : super.extractGst(body);
    }

    @Override
    protected BigDecimal extractDeliveryFee(String body) {
        Matcher m = billLine("delivery\\s*(?:charge|fee)").matcher(body);
        return m.find() ? parseMoney(m.group(1)) : super.extractDeliveryFee(body);
    }

    @Override
    protected BigDecimal extractDiscount(String body) {
        Matcher m = billLine("discount").matcher(body);
        return m.find() ? parseMoney(m.group(1)) : super.extractDiscount(body);
    }
}

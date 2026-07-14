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
 * Aggregator-tuned parser for Yatri Restro (code {@code YATRI_RESTRO}). Its real
 * "Order Confirmation" email flattens to a fully VERTICAL layout: every field is a
 * label on one line and its value on the NEXT line, e.g.
 * <pre>
 *   TRAIN No /NAME
 *   12808 / SAMTA EXPRESS
 *   COACH/BERTH
 *   B4 / 57
 *   Station Code/Name
 *   VGLJ / VIRANGANA LAKSHMIBAI JHANSI JN
 * </pre>
 * The {@link GenericIrctcEmailParser}'s horizontal field regexes handle most of these
 * next-line values already (order id, phone, customer name, coach/berth, payment mode,
 * delivery date/time, bill breakdown, grand total). Two labels defeat it, because the
 * horizontal regex captures the label's own trailing fragment as a spurious value:
 * <ul>
 *   <li>{@code TRAIN No /NAME} → the generic returns the fragment "NAME" as the train
 *       name (and no number); and</li>
 *   <li>{@code Station Code/Name} → the generic returns the fragment "/Name" as the
 *       station name (and no code).</li>
 * </ul>
 * Both are recovered here from the next line. The item table is also vertical and
 * wraps the item description across lines, which the generic vertical/columnar item
 * parsers can't follow, so it is parsed here too — capturing name AND description.
 *
 * <p>Registered ahead of the generic parser (but after the other tuned parsers) so it
 * wins for YATRI_RESTRO mail.
 */
@Component
@Order(9)
public class YatriRestroEmailParser extends GenericIrctcEmailParser {

    private static final Pattern FIVE_DIGITS = Pattern.compile("\\d{5}");

    // Item-table markers. The block opens with an "Order Item Details:" header, then a
    // run of one-per-line column-header labels (Item, Description, Price, Quantity, Amount),
    // and closes at the "Sub Total" summary line.
    private static final Pattern ITEM_SECTION = Pattern.compile(
            "(?i)^order\\s*item\\s*details\\b.*|(?i)^item\\s*details\\b.*");
    private static final Pattern HEADER_LABEL = Pattern.compile(
            "(?i)^(?:item|description|price|quantity|qty|amount|sl\\.?#?|sr\\.?\\s*no\\.?|no\\.?)$");
    // A whole line that is purely money: currency-prefixed number, or a bare decimal.
    private static final Pattern MONEY_LINE = Pattern.compile(
            "(?i)^(?:₹|Rs\\.?|INR)[ \\t\\u00a0]*[0-9][0-9,]*(?:\\.[0-9]{1,2})?$"
                    + "|^[0-9][0-9,]*\\.[0-9]{1,2}$");
    // A whole line that is purely a quantity: an integer, optionally "xN".
    private static final Pattern INT_LINE = Pattern.compile("(?i)^x?[ \\t\\u00a0]*([0-9]{1,4})$");
    // Bill/summary labels that close the item block.
    private static final Pattern SUMMARY_LINE = Pattern.compile(
            "(?i)^(?:sub\\s*total|grand\\s*total|total|gst|discount|delivery|payable"
                    + "|balance|cashback|charge|amount\\s*to\\s*collect|net\\b)\\b");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "YATRI_RESTRO".equalsIgnoreCase(agg.getCode());
    }

    // ---- train --------------------------------------------------------------

    /**
     * Recover {@code {number, name}} from the vertical "TRAIN No /NAME" label whose
     * value ("12808 / SAMTA EXPRESS") is on the next line. Only accept a candidate that
     * yields a real 5-digit train number, so the generic parser's "NAME" label fragment
     * is never emitted as the train name.
     */
    @Override
    protected String[] extractTrain(String body) {
        for (String cand : labelValues(body,
                "train\\s*no\\.?\\s*/?\\s*name|train\\s*no\\.?|train\\s*number|train\\s*name|train")) {
            String[] t = splitTrainCell(cand);
            if (t[0] != null) {
                return t;
            }
        }
        return super.extractTrain(body);
    }

    /** Split a train cell like "12808 / SAMTA EXPRESS" into {number, name}. */
    private static String[] splitTrainCell(String seg) {
        if (seg == null) {
            return new String[]{null, null};
        }
        seg = seg.trim();
        Matcher num = FIVE_DIGITS.matcher(seg);
        String number = num.find() ? num.group() : null;
        String name = seg.replaceFirst("\\d{5}", "").replaceAll("[/:]", " ")
                .replaceAll("\\s+", " ").trim();
        return new String[]{number, name.isBlank() ? null : name};
    }

    // ---- station ------------------------------------------------------------

    /**
     * Recover {@code {code, name}} from the vertical "Station Code/Name" label whose
     * value ("VGLJ / VIRANGANA LAKSHMIBAI JHANSI JN") is on the next line, rather than
     * the generic parser's "/Name" label fragment.
     */
    @Override
    protected String[] extractStation(String body) {
        for (String cand : labelValues(body,
                "station\\s*code\\s*/?\\s*name|station\\s*name\\s*/?\\s*code"
                        + "|delivery\\s*station|station\\s*name|station\\s*code|station")) {
            if (cand != null && !cand.isBlank()) {
                String[] s = parseStation(cand.trim());
                if (s[0] != null || s[1] != null) {
                    return s;
                }
            }
        }
        return super.extractStation(body);
    }

    // ---- line items ---------------------------------------------------------

    /**
     * Parse Yatri Restro's vertical item table, in which the columns arrive one value
     * per line and the item DESCRIPTION wraps across lines:
     * <pre>
     *   Item / Description / Price / Quantity / Amount   ← header labels, one per line
     *   Veg Thali                                        ← name
     *   Paneer Gravy Rice 3 Roti Achar Salad Sweet MF spoon
     *   Napkin                                           ← description (wraps → joined)
     *   ₹ 165                                            ← unit price
     *   2                                                ← quantity
     *   ₹ 330                                            ← line amount (consumed)
     *   ...
     *   Sub Total                                        ← summary → stop
     * </pre>
     * Falls back to the generic item extraction when no such block is found.
     */
    @Override
    protected List<OrderItem> extractItems(String body) {
        List<OrderItem> items = extractYatriItems(body);
        return items.isEmpty() ? super.extractItems(body) : items;
    }

    private List<OrderItem> extractYatriItems(String body) {
        List<String> lines = nonEmptyLines(body);
        int n = lines.size();
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (ITEM_SECTION.matcher(lines.get(i)).matches()) {
                start = i + 1;
                break;
            }
        }
        List<OrderItem> items = new ArrayList<>();
        if (start < 0) {
            return items; // no Yatri item block → let the generic parser try.
        }
        // Skip the one-per-line column-header labels (Item, Description, Price, …).
        while (start < n && HEADER_LABEL.matcher(lines.get(start)).matches()) {
            start++;
        }
        int i = start;
        while (i < n) {
            String name = lines.get(i);
            if (SUMMARY_LINE.matcher(name).find()) {
                break; // reached the bill breakdown.
            }
            if (!HAS_LETTER.matcher(name).find() || MONEY_LINE.matcher(name).matches()) {
                i++;
                continue;
            }
            i++;
            // Description lines wrap until the unit-price (money) line is reached.
            List<String> descParts = new ArrayList<>();
            while (i < n && !MONEY_LINE.matcher(lines.get(i)).matches()
                    && !SUMMARY_LINE.matcher(lines.get(i)).find()
                    && !INT_LINE.matcher(lines.get(i)).matches()) {
                descParts.add(lines.get(i));
                i++;
            }
            if (i >= n || !MONEY_LINE.matcher(lines.get(i)).matches()) {
                break; // malformed — no price found for this name.
            }
            BigDecimal price = firstMoney(lines.get(i));
            i++;
            int qty = 1;
            if (i < n) {
                Matcher q = INT_LINE.matcher(lines.get(i));
                if (q.matches()) {
                    qty = Integer.parseInt(q.group(1));
                    i++;
                }
            }
            if (i < n && MONEY_LINE.matcher(lines.get(i)).matches()) {
                i++; // consume the line-amount cell.
            }
            String description = descParts.isEmpty() ? null : String.join(" ", descParts);
            items.add(new OrderItem(name, description, Math.max(qty, 1), price));
        }
        return items;
    }

    private static List<String> nonEmptyLines(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String l : body.split("\\r?\\n")) {
            String s = l.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}

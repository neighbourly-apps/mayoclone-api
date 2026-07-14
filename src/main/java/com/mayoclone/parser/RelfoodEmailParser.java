package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for RELFOOD (code {@code RELFOOD}). Its header fields are
 * the ordinary vertical label→value pairs the {@link GenericIrctcEmailParser} already
 * handles (order id, customer name, contact, train, coach/seat, payment mode, amount
 * to collect), so those are inherited. RELFOOD is special only in three places, all
 * because the live HTML flattens differently from other aggregators:
 * <ul>
 *   <li>the item + bill table arrives in one of two shapes — the older render puts
 *       the row on ONE line
 *       ({@code Item Price Quantity Total <name/desc> <price> <qty> <total> Sub Total …});
 *       the newer render puts one field per line and GLUES the numbers onto their
 *       labels/description ({@code ItemPriceQuantityTotal} header, {@code …Cutlery1941194}
 *       item tail, {@code Sub Total194}, {@code GST10}, {@code Total204});</li>
 *   <li>the station sits on a {@code "Station Name &amp; Code : NAME (CODE)"} line; and</li>
 *   <li>the delivery date/time is {@code "Delivery Date &amp; Time : 7/13/2026 &amp; 20:40"}.</li>
 * </ul>
 * Registered ahead of the generic parser so it wins for RELFOOD mail.
 */
@Component
@Order(10)
public class RelfoodEmailParser extends GenericIrctcEmailParser {

    // "Station Name & Code : VIRANGANA LAKSHMIBAI JHANSI JN (VGLJ)" — the "&" may be a
    // literal ampersand or the still-encoded "&amp;" the live mail carries.
    private static final Pattern RELFOOD_STATION = Pattern.compile(
            "(?i)station\\s*name\\s*(?:&(?:amp;)?)?\\s*code\\s*[:#-]?\\s*([^\\r\\n]+)");

    // "Delivery Date & Time : 7/13/2026 & 20:40" — US M/d/yyyy date, "&" or "&amp;" separator.
    private static final Pattern RELFOOD_DATETIME = Pattern.compile(
            "(?i)delivery\\s*date\\s*(?:&(?:amp;)?)?\\s*time\\s*[:#-]?\\s*"
                    + "([0-9]{1,2}/[0-9]{1,2}/[0-9]{4})\\s*(?:&(?:amp;)?)\\s*([0-9]{1,2}:[0-9]{2})");

    // The item table sits between an "Item Price Quantity Total" header — whose words
    // may be space-separated ("Item Price Quantity Total") OR glued into one token
    // ("ItemPriceQuantityTotal") in the newer one-field-per-line render — and the
    // "Sub Total" summary line (itself possibly glued, e.g. "Sub Total194"). DOTALL so
    // the captured block may span multiple lines.
    private static final Pattern RELFOOD_ITEM_BLOCK = Pattern.compile(
            "(?is)Item\\s*Price\\s*Quantity\\s*Total\\s*(.*?)\\s*Sub\\s*Total");
    // Old flattened SINGLE-LINE layout: the whole item sits on one line with the
    // "<price> <qty> <total>" columns space-separated after the (multi-word) name/description.
    private static final Pattern RELFOOD_ITEM_SPACED = Pattern.compile(
            "^(.*?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+([0-9]+)\\s+([0-9]+(?:\\.[0-9]+)?)$");
    // New one-field-per-line layout: name on its own line, then a description whose
    // tail has "<price><qty><total>" glued on with NO separators ("…Cutlery1941194").
    // Group 1 = name/description (ends on a non-digit); group 2 = the glued digit run.
    private static final Pattern RELFOOD_ITEM_GLUED = Pattern.compile(
            "(?s)^(.*\\D)([0-9]+)$");

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "RELFOOD".equalsIgnoreCase(agg.getCode());
    }

    @Override
    protected String[] extractStation(String body) {
        Matcher m = RELFOOD_STATION.matcher(body);
        if (m.find()) {
            return parseStation(m.group(1).trim());
        }
        return super.extractStation(body);
    }

    @Override
    protected LocalDate extractDeliveryDate(String body) {
        Matcher m = RELFOOD_DATETIME.matcher(body);
        if (m.find()) {
            // RELFOOD is the one aggregator that genuinely uses US M/d/yyyy (its "7/13/2026"
            // proves it — 13 can't be a month). Parse it explicitly here so the generic
            // dd/MM-first parseDate doesn't flip an ambiguous "7/9/2026" to 7 Sep.
            try {
                return LocalDate.parse(m.group(1),
                        java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy", java.util.Locale.ENGLISH));
            } catch (RuntimeException ignored) {
                LocalDate d = parseDate(m.group(1));
                if (d != null) {
                    return d;
                }
            }
        }
        return super.extractDeliveryDate(body);
    }

    @Override
    protected String extractSlot(String body) {
        Matcher m = RELFOOD_DATETIME.matcher(body);
        if (m.find()) {
            return m.group(2);
        }
        return super.extractSlot(body);
    }

    @Override
    protected List<OrderItem> extractItems(String body) {
        Matcher block = RELFOOD_ITEM_BLOCK.matcher(body);
        if (block.find()) {
            String blockText = block.group(1).trim();
            // (a) GLUED render ("…Cutlery1941194", numbers welded onto the description).
            OrderItem glued = parseGluedItem(blockText);
            if (glued != null) {
                return List.of(glued);
            }
            // (b) SINGLE flattened line "<name> <price> <qty> <total>" (one item, no newline).
            if (!blockText.contains("\n")) {
                Matcher spaced = RELFOOD_ITEM_SPACED.matcher(blockText);
                if (spaced.matches()) {
                    return List.of(new OrderItem(collapse(spaced.group(1)),
                            Math.max(Integer.parseInt(spaced.group(3)), 1), parseMoney(spaced.group(2))));
                }
            }
        }
        // (c) MULTI-LINE columnar layout — the generic columnar extractor separates each dish
        // name from its description row and handles multiple items cleanly. Delegate to it.
        return super.extractItems(body);
    }

    /** Glued one-field-per-line layout: a single "<name/desc><price><qty><total>" run. */
    private OrderItem parseGluedItem(String blockText) {
        Matcher glued = RELFOOD_ITEM_GLUED.matcher(blockText);
        if (glued.matches()) {
            int[] pqt = splitGluedColumns(glued.group(2));
            if (pqt != null) {
                return new OrderItem(collapse(glued.group(1)), Math.max(pqt[1], 1),
                        BigDecimal.valueOf(pqt[0]));
            }
        }
        return null;
    }

    /** Newlines/runs of whitespace → a single space; trimmed. */
    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Split a glued {@code <price><qty><total>} digit run (e.g. {@code "1941194"} →
     * price 194, qty 1, total 194) using the invariant {@code price × qty == total}.
     * Among valid splits, prefers the fewest-digit quantity, tie-broken by the largest
     * total. Returns {@code {price, qty, total}}, or {@code null} when none satisfies it.
     */
    private static int[] splitGluedColumns(String digits) {
        int len = digits.length();
        int[] best = null;
        int bestQtyLen = Integer.MAX_VALUE;
        for (int i = 1; i < len - 1; i++) {              // price = digits[0, i)
            for (int j = 1; i + j < len; j++) {          // qty   = digits[i, i + j)
                long price = Long.parseLong(digits.substring(0, i));
                long qty = Long.parseLong(digits.substring(i, i + j));
                long total = Long.parseLong(digits.substring(i + j));
                if (price >= 1 && qty >= 1 && price * qty == total
                        && (j < bestQtyLen || (j == bestQtyLen && best != null && total > best[2]))) {
                    best = new int[]{(int) price, (int) qty, (int) total};
                    bestQtyLen = j;
                }
            }
        }
        return best;
    }

    // Bill breakdown + grand total: the labels and their values sit on the SAME
    // (collapsed) line, so read the number after each label anywhere in the body.
    @Override
    protected BigDecimal extractSubtotal(String body) {
        return relfoodNum(body, "sub\\s*total");
    }

    @Override
    protected BigDecimal extractGst(String body) {
        return relfoodNum(body, "gst");
    }

    @Override
    protected BigDecimal extractDeliveryFee(String body) {
        return relfoodNum(body, "delivery\\s*(?:fee|charge)");
    }

    @Override
    protected BigDecimal extractAmount(String body) {
        // The final "Total <n>" (NOT "Sub Total"): the GST-inclusive grand total.
        BigDecimal grand = relfoodNum(body, "(?<!sub\\s)(?<!sub)total");
        if (grand != null) {
            return grand;
        }
        return super.extractAmount(body);
    }

    /** Number after {@code labelRegex} anywhere in the body; null when absent. */
    private BigDecimal relfoodNum(String body, String labelRegex) {
        Matcher m = Pattern.compile("(?i)" + labelRegex
                + "\\s*[:#-]?\\s*(?:₹|Rs\\.?|INR)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(body);
        return m.find() ? parseMoney(m.group(1)) : null;
    }
}

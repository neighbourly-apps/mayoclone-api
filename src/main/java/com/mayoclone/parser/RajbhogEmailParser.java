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
 * Aggregator-tuned parser for RajBhog Khana (code {@code RAJBHOGKHANA}).
 *
 * <p>RajBhog's "Order Invoice" mail flattens to the strict <em>vertical</em>
 * layout (one label/value per line). Its header fields are ordinary label→value
 * pairs the {@link GenericIrctcEmailParser} already handles — customer name +
 * contact, "Train: 22538 / KUSHINAGAR EXP", "Coach / Berth: S1 / 48", "Delivery
 * Station: VGLJ / VIRANGANA LAKSHMIBAI JHANSI JN", "Payment: CASH_ON_DELIVERY"
 * (→ COD), the Delivery Date and 19:15 slot — so those are inherited. RajBhog is
 * special in four places where the live layout defeats the generic pipeline:
 * <ul>
 *   <li><b>Order id.</b> The mail opens with the header line "Rajbhog Order
 *       Invoice", which lures the generic {@code Invoice&lt;value&gt;} rule into
 *       grabbing the stray next word ("Booking"). The real id is the RBK… number
 *       on the "Invoice RBK001734622 / 2464656924" line, pinned here.</li>
 *   <li><b>Line items.</b> The item table is a 7-column grid — SL#, Item,
 *       Description, Qty, Price, GST, Amount — but every header token AND every
 *       cell is on its own line, so neither the generic vertical nor columnar
 *       item parser recognises it (they drop the row entirely). Parsed here.</li>
 *   <li><b>GST / Delivery / Total.</b> The summary values sit on the line AFTER
 *       their label ("GST (5%)" → "7.55", "Delivery:" → "0", "Total:" → "159.00"),
 *       and the labels ("GST (5%)", "Delivery:", "Total:") don't match the generic
 *       label patterns — GST would even read the "5" out of "(5%)". Read here.</li>
 * </ul>
 * Subtotal and Discount are the plain vertical pairs the generic parser reads
 * correctly, so those stay inherited. Registered ahead of the generic parser so
 * it wins for RAJBHOGKHANA mail.
 */
@Component
@Order(10)
public class RajbhogEmailParser extends GenericIrctcEmailParser {

    // The real order id: "Invoice RBK001734622 / 2464656924" at the START of a
    // line. Anchoring on ^ skips the "Rajbhog Order Invoice" header (which starts
    // with "Rajbhog"), so the stray "Booking" is never captured.
    private static final Pattern RBK_INVOICE = Pattern.compile(
            "(?im)^\\s*Invoice\\s+([A-Za-z0-9]+)");

    // A whole line that is exactly the "Amount" column header (opens the grid).
    private static final Pattern AMOUNT_HEADER = Pattern.compile("(?i)^amount$");
    // The summary block that closes the grid ("Subtotal:", "Grand Total", "Total:").
    private static final Pattern GRID_END = Pattern.compile(
            "(?i)^(?:sub\\s*total|grand\\s*total|total)\\b.*");
    // A cell that is purely an integer (SL# index or Qty).
    private static final Pattern INT_CELL = Pattern.compile("^\\d+$");
    // A cell that is purely a number, optionally ₹/Rs/INR (Qty/Price/GST/Amount).
    private static final Pattern NUM_CELL = Pattern.compile(
            "(?i)^(?:₹|Rs\\.?|INR)?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "RAJBHOGKHANA".equalsIgnoreCase(agg.getCode());
    }

    @Override
    protected String extractOrderId(String body, String fallback) {
        Matcher m = RBK_INVOICE.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return super.extractOrderId(body, fallback);
    }

    /**
     * Parse RajBhog's vertical 7-column grid. Each item is: an integer SL# index,
     * a name line, zero+ description lines, then up to four numeric cells (Qty,
     * Price, GST, Amount). Unit price is the Price column (the 2nd number), qty the
     * 1st. Falls back to the generic parsers for any other layout.
     */
    @Override
    protected List<OrderItem> extractItems(String body) {
        List<String> lines = new ArrayList<>();
        for (String raw : body.split("\\r?\\n")) {
            String s = raw.strip();
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        int start = -1;
        int end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (start < 0) {
                if (AMOUNT_HEADER.matcher(lines.get(i)).matches()) {
                    start = i + 1;
                }
            } else if (GRID_END.matcher(lines.get(i)).matches()) {
                end = i;
                break;
            }
        }
        if (start < 0) {
            return super.extractItems(body);
        }

        List<OrderItem> items = new ArrayList<>();
        int i = start;
        while (i < end) {
            // An item begins at its integer SL# index.
            if (!INT_CELL.matcher(lines.get(i)).matches()) {
                i++;
                continue;
            }
            i++;
            // Name (first text line) then any description lines, up to the numbers.
            String name = null;
            while (i < end && HAS_LETTER.matcher(lines.get(i)).find()) {
                if (name == null) {
                    name = lines.get(i);
                }
                i++;
            }
            // Numeric columns: Qty, Price, GST, Amount (stop at the next SL# index).
            List<BigDecimal> nums = new ArrayList<>();
            while (i < end && nums.size() < 4 && NUM_CELL.matcher(lines.get(i)).matches()) {
                nums.add(parseMoney(lines.get(i)));
                i++;
            }
            if (name != null && !nums.isEmpty()) {
                int qty = Math.max(nums.get(0).intValue(), 1);
                BigDecimal unit = nums.size() >= 2 ? nums.get(1) : nums.get(0);
                items.add(new OrderItem(name, qty, unit));
            }
        }
        return items.isEmpty() ? super.extractItems(body) : items;
    }

    @Override
    protected BigDecimal extractGst(String body) {
        // "GST (5%)" label then value on the next line; the "5" in "(5%)" must be
        // ignored, and the bare "GST" grid header (no "%") must not match.
        BigDecimal v = valueAfterLabel(body, "(?i)^gst\\b.*%.*");
        return v != null ? v : super.extractGst(body);
    }

    @Override
    protected BigDecimal extractDeliveryFee(String body) {
        // The fee line is "Delivery:" (not "Delivery Charge/Fee"); require the
        // whole line so "Delivery Date:" / "Delivery Station:" never match.
        BigDecimal v = valueAfterLabel(body, "(?i)^delivery\\s*:?$");
        return v != null ? v : super.extractDeliveryFee(body);
    }

    @Override
    protected BigDecimal extractAmount(String body) {
        // Grand total is the "Total:" line's value (not "Subtotal:"); the numbers
        // carry no currency symbol so the generic ₹-based fallback can't see them.
        BigDecimal v = valueAfterLabel(body, "(?i)^total\\s*:?$");
        return v != null ? v : super.extractAmount(body);
    }

    /**
     * First money value on the non-empty line AFTER the first whole line matching
     * {@code lineLabelRegex}; null when the label or a following number is absent.
     */
    private BigDecimal valueAfterLabel(String body, String lineLabelRegex) {
        String[] lines = body.split("\\r?\\n");
        Pattern label = Pattern.compile(lineLabelRegex);
        for (int i = 0; i < lines.length; i++) {
            if (label.matcher(lines[i].strip()).matches()) {
                for (int j = i + 1; j < lines.length; j++) {
                    String s = lines[j].strip();
                    if (!s.isEmpty()) {
                        return firstMoney(s);
                    }
                }
            }
        }
        return null;
    }
}

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
 *   <li>the whole item + bill table collapses onto ONE line
 *       ({@code Item Price Quantity Total <name/desc> <price> <qty> <total> Sub Total …});</li>
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

    // Item table between the "Item Price Quantity Total" header and "Sub Total".
    private static final Pattern RELFOOD_ITEM_BLOCK = Pattern.compile(
            "(?i)Item\\s+Price\\s+Quantity\\s+Total\\s+(.*?)\\s+Sub\\s*Total\\b");
    // A single item's trailing "<price> <qty> <total>"; the rest is name (+ description).
    private static final Pattern RELFOOD_ITEM = Pattern.compile(
            "^(.*?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+([0-9]+)\\s+([0-9]+(?:\\.[0-9]+)?)$");

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
            LocalDate d = parseDate(m.group(1));
            if (d != null) {
                return d;
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
            Matcher item = RELFOOD_ITEM.matcher(block.group(1).trim());
            if (item.matches()) {
                String name = item.group(1).trim();
                BigDecimal price = parseMoney(item.group(2));
                int qty = Integer.parseInt(item.group(3));
                return List.of(new OrderItem(name, Math.max(qty, 1), price));
            }
        }
        return super.extractItems(body);
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

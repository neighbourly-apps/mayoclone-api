package com.mayoclone.parser;

import com.mayoclone.domain.OrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared regex-based extraction helpers for the concrete aggregator parsers.
 * All helpers degrade gracefully: they return sensible defaults rather than
 * throwing when a field is absent from the email body.
 */
public abstract class AbstractRegexEmailParser implements AggregatorEmailParser {

    // "Order ID: ABC123", "Order #12345", "Order No: 998"
    private static final Pattern ORDER_ID = Pattern.compile(
            "(?:order\\s*(?:id|no\\.?|number)\\s*[:#-]?\\s*|#)([A-Za-z0-9-]+)",
            Pattern.CASE_INSENSITIVE);

    // "Total: ₹1,234.50", "Rs. 320", "₹ 450"
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:grand\\s*total|total|amount)?\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    // "Customer: Jane Doe" or "Hi Jane,"
    private static final Pattern CUSTOMER_LABEL = Pattern.compile(
            "customer\\s*(?:name)?\\s*[:-]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOMER_GREETING = Pattern.compile(
            "^\\s*Hi\\s+(.+?),", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // "2 x Paneer Tikka - ₹320" / "1 X Coke – Rs. 60"
    private static final Pattern LINE_ITEM = Pattern.compile(
            "(\\d+)\\s*[xX*]\\s*(.+?)\\s*[-–:]\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    protected String extractOrderId(String body, String fallback) {
        Matcher m = ORDER_ID.matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : fallback;
    }

    protected BigDecimal extractAmount(String body) {
        if (body == null) {
            return BigDecimal.ZERO;
        }
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

    protected String extractCustomer(String body) {
        if (body == null) {
            return "Unknown";
        }
        Matcher m = CUSTOMER_LABEL.matcher(body);
        if (m.find()) {
            return m.group(1).trim();
        }
        Matcher g = CUSTOMER_GREETING.matcher(body);
        if (g.find()) {
            return g.group(1).trim();
        }
        return "Unknown";
    }

    protected List<OrderItem> extractItems(String body) {
        List<OrderItem> items = new ArrayList<>();
        if (body == null) {
            return items;
        }
        Matcher m = LINE_ITEM.matcher(body);
        while (m.find()) {
            int qty = Integer.parseInt(m.group(1));
            String name = m.group(2).trim();
            BigDecimal price = parseMoney(m.group(3));
            items.add(new OrderItem(name, qty, price));
        }
        return items;
    }

    protected Instant now() {
        return Instant.now();
    }

    private BigDecimal parseMoney(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }
}

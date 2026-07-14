package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for RailRestro. Every field is the ordinary layout the
 * {@link GenericIrctcEmailParser} already handles, EXCEPT the bill breakdown, whose labels
 * are inverted: RailRestro's <b>"Total:"</b> line is the pre-tax FOOD subtotal, while its
 * <b>"Subtotal:"</b> line is actually the GST-INCLUSIVE grand total (equal to "Payable
 * Total:"). The generic parser reads "Subtotal:" as the subtotal, which makes the displayed
 * breakdown wrong (Subtotal == Total, and Subtotal + GST &gt; Total). Here we pin the subtotal
 * to the "Total:" line so the breakdown reconciles. Grand total (amount) is unchanged — the
 * generic parser already reads it from "Payable Total:".
 */
@Component
@Order(9)
public class RailRestroEmailParser extends GenericIrctcEmailParser {

    // The FOOD total line: a line that STARTS with "Total:" (so "Subtotal:" and "Payable
    // Total:" are excluded). The amount may sit on the same line or the next (\\s spans the
    // newline); the currency token is required so the match locks onto the real amount.
    private static final Pattern FOOD_TOTAL = Pattern.compile(
            "(?im)^\\s*total\\s*:\\s*(?:₹|Rs\\.?|INR)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "RAILRESTRO".equalsIgnoreCase(agg.getCode());
    }

    @Override
    protected BigDecimal extractSubtotal(String body) {
        Matcher m = FOOD_TOTAL.matcher(body);
        if (m.find()) {
            return parseMoney(m.group(1));
        }
        return super.extractSubtotal(body);
    }
}

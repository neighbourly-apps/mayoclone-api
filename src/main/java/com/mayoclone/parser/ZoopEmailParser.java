package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for Zoop (code {@code ZOOP}). It reuses the whole
 * {@link GenericIrctcEmailParser} extraction pipeline and only refines the
 * order-id pattern, since Zoop labels the reference as "Booking ID" (or "Order
 * Ref") as well as the generic "Order ID". Registered ahead of the generic
 * parser so it wins for ZOOP mail.
 */
@Component
@Order(10)
public class ZoopEmailParser extends GenericIrctcEmailParser {

    // Zoop-specific: "Booking ID: ZOOP1023" / "Order Ref: ZP-77"
    private static final Pattern ZOOP_ORDER_ID = Pattern.compile(
            "(?:booking\\s*id|order\\s*ref(?:erence)?)\\s*[:#-]?\\s*([A-Za-z0-9-]+)",
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
}

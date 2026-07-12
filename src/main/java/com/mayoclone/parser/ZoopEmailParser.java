package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for Zoop (code {@code ZOOP}). It reuses the whole
 * {@link GenericIrctcEmailParser} extraction pipeline (which already handles
 * Zoop's name-first train "Kurj Udz Exp/ 19665" and Prepaid detection) and only
 * refines the two things that are Zoop-specific:
 * <ul>
 *   <li>the order id, labeled "ZOOP Txn. No." (also legacy "Booking ID"/"Order Ref"), and</li>
 *   <li>the delivery station, given on an "At : &lt;name&gt;/ &lt;code&gt;" line.</li>
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
}

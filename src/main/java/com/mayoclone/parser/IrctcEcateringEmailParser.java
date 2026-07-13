package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for the IRCTC eCatering vendor order-confirmation email
 * (aggregator code {@code IRCTC_ECATERING}).
 *
 * <p>These emails arrive from IRCTC's own eCatering system in a strictly
 * <em>vertical</em> layout: every field label sits on its own line and its value
 * on the very next line, e.g.
 * <pre>
 *   ORDER No
 *   2465449929
 *   TRAIN No
 *   12190
 *   COACH NO / SEAT NO
 *   B5/ 64
 *   DELIVERY STATION
 *   VIRANGANA LAKSHMIBAI JHANSI JN
 * </pre>
 * The whole {@link GenericIrctcEmailParser} pipeline already copes with this shape
 * (vertical coach/seat, station, MOBILE-No phone, JOURNEY DATE, DELIVERY TIME,
 * CASH_ON_DELIVERY→COD, the ₹-per-line item block and the Sub Total / GST /
 * Delivery Charge / Discount* / "Grand Total (Inclusive of all taxes)" bill), so
 * this subclass only hardens the two fields where a loose generic pattern could
 * capture the wrong token in this specific format:
 * <ul>
 *   <li><b>order id</b> — pinned to the digits following "ORDER No"/"Order Number"
 *       so it can never latch onto a stray word (e.g. "for"); and</li>
 *   <li><b>train number</b> — pinned to the 5-digit value following "TRAIN No"
 *       ONLY, never the bare "No" label or any other text; an optional
 *       "/ &lt;name&gt;" on the same line becomes the train name.</li>
 * </ul>
 * Registered ahead of the generic parser (lower {@link Order}) so it wins for
 * IRCTC_ECATERING mail.
 */
@Component
@Order(10)
public class IrctcEcateringEmailParser extends GenericIrctcEmailParser {

    /** The aggregator code this parser is dedicated to. */
    private static final String CODE = "IRCTC_ECATERING";

    // "ORDER No\n2465449929" or inline "having Order Number 2465449929 on ...".
    // \s spans the newline that separates the vertical label from its value; the
    // value is the numeric order id (5+ digits), so a following word can never win.
    private static final Pattern ORDER_ID = Pattern.compile(
            "ORDER\\s*(?:No\\.?|Number)\\s*[:#-]?\\s*(\\d{5,})",
            Pattern.CASE_INSENSITIVE);

    // "TRAIN No\n12190" (optionally "12190 / GARIB RATH" on the value line). The
    // number group is EXACTLY five digits; the optional name is only text after a
    // "/" or "-" ON THE SAME LINE, so a bare "TRAIN No" never yields a name and the
    // train number is never any surrounding text.
    private static final Pattern TRAIN = Pattern.compile(
            "TRAIN\\s*No\\.?\\s*[:#-]?\\s*(\\d{5})"
                    + "(?:[^\\S\\r\\n]*[/\\-][^\\S\\r\\n]*([A-Za-z][^\\r\\n]*?))?"
                    + "[^\\S\\r\\n]*(?=\\r|\\n|$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && CODE.equals(agg.getCode());
    }

    @Override
    protected String extractOrderId(String body, String fallback) {
        Matcher m = ORDER_ID.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return super.extractOrderId(body, fallback);
    }

    /** @return {trainNumber, trainName}; number is the 5-digit TRAIN No value only. */
    @Override
    protected String[] extractTrain(String body) {
        Matcher m = TRAIN.matcher(body);
        if (m.find()) {
            String name = m.group(2) == null ? null : m.group(2).trim();
            return new String[]{m.group(1), (name == null || name.isEmpty()) ? null : name};
        }
        return super.extractTrain(body);
    }
}

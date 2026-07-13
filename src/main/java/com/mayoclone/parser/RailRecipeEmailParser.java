package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator-tuned parser for RailRecipe (code {@code RAILRECIPE}).
 *
 * <p>RailRecipe order-confirmation mail flattens to the strict <em>vertical</em>
 * layout — every field label and every value sits on its OWN line — which the
 * {@link GenericIrctcEmailParser} vertical-layout pipeline already handles end to
 * end for this format:
 * <pre>
 *   Order No.            → 1826362           (order id)
 *   PNR No               → 2953991789        (pnr)
 *   Mobile No.           → 8850989379        (phone-only; no customer name)
 *   Train No.            → 12190             (5-digit number, no name)
 *   Coach/Seat           → M1/74             (coach + berth)
 *   Delivery Station     → VGLJ              (station code)
 *   Delivery Time (ETA)  → Jul 13,2026 19:30 (date + slot)
 *   Journey Date         → 2026-07-13
 *   PAYMENT STATUS       → CASH_ON_DELIVERY  (→ COD)
 *   Item Name/Price/Quantity/Amount table, then Subtotal/Discount/Delivery/GST/Grand Total.
 * </pre>
 *
 * <p>The only RailRecipe-specific refinement is pinning the external order id to
 * the "Order No." digits. The generic parser also happens to catch it from the
 * "…having Order Number 1826362…" preamble, but that preamble is not guaranteed;
 * the "Order No." field is. All other fields are inherited unchanged. Registered
 * ahead of the generic parser so it wins for RAILRECIPE mail.
 */
@Component
@Order(10)
public class RailRecipeEmailParser extends GenericIrctcEmailParser {

    // "Order No." then the digits — inline ("Order No.  1826362") or vertical
    // ("Order No.\n\n1826362"); \s spans the newline. Anchored on "No" so the
    // "Order Number …" preamble (No != Number) and "Order Date" never match.
    private static final Pattern RR_ORDER_NO = Pattern.compile(
            "Order\\s*No\\.?\\s*[:#-]?\\s*(\\d{3,})", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(Aggregator agg, String from, String subject) {
        return agg != null && "RAILRECIPE".equalsIgnoreCase(agg.getCode());
    }

    @Override
    protected String extractOrderId(String body, String fallback) {
        Matcher m = RR_ORDER_NO.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return super.extractOrderId(body, fallback);
    }
}

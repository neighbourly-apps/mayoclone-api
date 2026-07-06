package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/** Parses Swiggy order-confirmation emails. */
@Component
@Order(20)
public class SwiggyEmailParser extends AbstractRegexEmailParser {

    private static final Pattern SUBJECT_HINT = Pattern.compile(
            "swiggy", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String from, String subject) {
        if (from != null && from.toLowerCase().contains("swiggy")) {
            return true;
        }
        return subject != null && SUBJECT_HINT.matcher(subject).find();
    }

    @Override
    public ParsedOrder parse(String from, String subject, String body, String messageId) {
        String orderId = extractOrderId(body, "SW-" + Math.abs(String.valueOf(messageId).hashCode()));
        BigDecimal amount = extractAmount(body);
        String customer = extractCustomer(body);
        List<OrderItem> items = extractItems(body);

        return new ParsedOrder(
                Aggregator.SWIGGY,
                orderId,
                customer,
                amount,
                "INR",
                now(),
                "RECEIVED",
                items,
                subject,
                messageId
        );
    }
}

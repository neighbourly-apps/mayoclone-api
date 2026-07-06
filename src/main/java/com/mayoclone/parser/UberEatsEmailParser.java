package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Light stub parser for Uber Eats. It still extracts what it can via the
 * shared regex helpers, but is intentionally minimal. Ordered last so the
 * more specific Zomato/Swiggy parsers win when senders overlap.
 */
@Component
@Order(100)
public class UberEatsEmailParser extends AbstractRegexEmailParser {

    @Override
    public boolean supports(String from, String subject) {
        if (from != null && from.toLowerCase().contains("uber")) {
            return true;
        }
        return subject != null && subject.toLowerCase().contains("uber eats");
    }

    @Override
    public ParsedOrder parse(String from, String subject, String body, String messageId) {
        String orderId = extractOrderId(body, "UE-" + Math.abs(String.valueOf(messageId).hashCode()));
        BigDecimal amount = extractAmount(body);
        String customer = extractCustomer(body);
        List<OrderItem> items = extractItems(body);

        return new ParsedOrder(
                Aggregator.UBER_EATS,
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

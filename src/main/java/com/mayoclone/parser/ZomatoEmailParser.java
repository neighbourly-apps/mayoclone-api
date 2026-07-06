package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/** Parses Zomato order-confirmation emails. */
@Component
@Order(10)
public class ZomatoEmailParser extends AbstractRegexEmailParser {

    // Subject fallback when the sender domain isn't obviously Zomato.
    private static final Pattern SUBJECT_HINT = Pattern.compile(
            "zomato|new order.*#\\d+", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String from, String subject) {
        if (from != null && from.toLowerCase().contains("zomato")) {
            return true;
        }
        return subject != null && SUBJECT_HINT.matcher(subject).find();
    }

    @Override
    public ParsedOrder parse(String from, String subject, String body, String messageId) {
        String orderId = extractOrderId(body, "ZO-" + Math.abs(String.valueOf(messageId).hashCode()));
        BigDecimal amount = extractAmount(body);
        String customer = extractCustomer(body);
        List<OrderItem> items = extractItems(body);

        return new ParsedOrder(
                Aggregator.ZOMATO,
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

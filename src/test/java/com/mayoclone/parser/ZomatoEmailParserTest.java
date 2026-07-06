package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZomatoEmailParserTest {

    private final ZomatoEmailParser parser = new ZomatoEmailParser();

    private static final String BODY = """
            Hi Priya,
            You have a new order on Zomato.
            Order ID: ZO12345
            Customer: Priya Sharma
            2 x Paneer Tikka - ₹320
            1 x Garlic Naan - ₹60
            Total: ₹700
            """;

    @Test
    void supportsBySender() {
        assertTrue(parser.supports("orders@zomato.com", "anything"));
        assertFalse(parser.supports("orders@swiggy.in", "lunch"));
    }

    @Test
    void extractsOrderIdAmountCustomerAndItems() {
        ParsedOrder parsed = parser.parse(
                "orders@zomato.com", "New Order", BODY, "<msg-1@zomato.com>");

        assertEquals(Aggregator.ZOMATO, parsed.aggregator());
        assertEquals("ZO12345", parsed.externalOrderId());
        assertEquals("Priya Sharma", parsed.customerName());
        assertEquals(0, new BigDecimal("700").compareTo(parsed.amount()));
        assertEquals("INR", parsed.currency());
        assertEquals("RECEIVED", parsed.status());
        assertEquals(2, parsed.items().size());

        OrderItem first = parsed.items().get(0);
        assertEquals("Paneer Tikka", first.getName());
        assertEquals(2, first.getQty());
        assertEquals(0, new BigDecimal("320").compareTo(first.getPrice()));
    }

    @Test
    void degradesGracefullyOnMissingFields() {
        ParsedOrder parsed = parser.parse(
                "orders@zomato.com", "New Order", "no useful content here", "<msg-2@zomato.com>");

        // Falls back rather than throwing.
        assertEquals("Unknown", parsed.customerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(parsed.amount()));
        assertTrue(parsed.items().isEmpty());
        assertFalse(parsed.externalOrderId().isBlank());
    }
}

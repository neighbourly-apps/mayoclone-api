package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericIrctcEmailParserTest {

    private final GenericIrctcEmailParser parser = new GenericIrctcEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static final String BODY = """
            Dear Rajesh Kumar,
            Your e-catering order is confirmed.
            Order ID: ZOOP1023
            PNR: 4512367890
            Train No.: 12951 Mumbai Rajdhani Express
            Coach: B3  Berth: 32
            Boarding: BCT
            Delivery Station: NDLS New Delhi
            Passenger: Rajesh Kumar
            Phone: 9876543210
            Delivery Date: 2026-07-08
            Delivery Time: 13:00-13:30
            Items:
            2 x Veg Biryani - ₹180
            1 x Masala Dosa - ₹90
            Total: ₹450
            """;

    @Test
    void extractsAllIrctcFields() {
        ParsedOrder p = parser.parse(
                agg("ZOOP"), "orders@zoopindia.com", "Zoop order confirmed", BODY,
                "<zoop-1@zoopindia.com>");

        assertEquals("ZOOP", p.aggregatorCode());
        assertEquals("ZOOP1023", p.externalOrderId());
        assertEquals("4512367890", p.pnr());
        assertEquals("12951", p.trainNumber());
        assertEquals("Mumbai Rajdhani Express", p.trainName());
        assertEquals("B3", p.coach());
        assertEquals("32", p.berth());
        assertEquals("BCT", p.boardingStationCode());
        assertEquals("NDLS", p.deliveryStationCode());
        assertEquals("New Delhi", p.deliveryStationName());
        assertEquals("Rajesh Kumar", p.passengerName());
        assertEquals("9876543210", p.passengerPhone());
        assertEquals(LocalDate.of(2026, 7, 8), p.deliveryDate());
        assertEquals("13:00-13:30", p.deliverySlot());
        assertEquals("INR", p.currency());
        assertEquals("CONFIRMED", p.status());
        assertEquals(0, new BigDecimal("450").compareTo(p.amount()));

        assertEquals(2, p.items().size());
        OrderItem first = p.items().get(0);
        assertEquals("Veg Biryani", first.getName());
        assertEquals(2, first.getQty());
        assertEquals(0, new BigDecimal("180").compareTo(first.getPrice()));
    }

    @Test
    void degradesGracefullyOnMissingFields() {
        ParsedOrder p = parser.parse(
                agg("COMESUM"), "orders@comesum.com", "Order", "no useful content here",
                "<msg-2@comesum.com>");

        assertEquals("Unknown", p.passengerName());
        assertNull(p.pnr());
        assertNull(p.trainNumber());
        assertNull(p.deliveryDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(p.amount()));
        assertTrue(p.items().isEmpty());
        // A fallback external id is always minted so dedup/unique-constraint work.
        assertTrue(p.externalOrderId().startsWith("COMESUM-"));
    }

    @Test
    void supportsAnyRoutedAggregator() {
        assertTrue(parser.supports(agg("GOFOODIE"), "x@gofoodieonline.com", "any"));
    }
}

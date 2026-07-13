package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Acceptance test against the REAL Zoop "Order Confirmation" email
 * ({@code src/test/resources/agg/ZOOP.txt}) — the verbatim HTML→text body the
 * parser receives, with every label, value and item cell on its own line. Asserts
 * the actual values in that file; these are the acceptance criteria for the Zoop
 * item-extraction tuning.
 */
class ZoopEmailParserTest {

    private final ZoopEmailParser parser = new ZoopEmailParser();

    private static Aggregator agg() {
        Aggregator a = new Aggregator();
        a.setCode("ZOOP");
        a.setName("Zoop");
        return a;
    }

    private static String load(String name) {
        try (InputStream in = ZoopEmailParserTest.class.getResourceAsStream("/agg/" + name)) {
            assertNotNull(in, "missing test fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void parsesRealZoopOrder() {
        String body = load("ZOOP.txt");
        ParsedOrder p = parser.parse(agg(), "orders@zoopindia.com",
                "Order Confirmation", body, "<real-zoop@zoopindia.com>");

        // identity + routing
        assertEquals("ZOOP", p.aggregatorCode());
        assertEquals("ZO36980746787092100", p.externalOrderId());
        assertNull(p.pnr());

        // train: 5-digit number only (name-first "Mahakal Sup Exp/ 20416")
        assertEquals("20416", p.trainNumber());
        assertEquals("Mahakal Sup Exp", p.trainName());

        // coach + seat/berth
        assertEquals("B4", p.coach());
        assertEquals("31", p.berth());

        // delivery station "At: <name>/ VGLJ"
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("Virangana Lakshmibai Jhansi Junction", p.deliveryStationName());

        // passenger + phone
        assertEquals("Vineet Singh", p.passengerName());
        assertEquals("7905796332", p.passengerPhone());

        // delivery date + slot
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("00:00", p.deliverySlot());

        // payment
        assertEquals("COD", p.paymentMode());
        assertMoney("459", p.amountToCollect());

        // ALL line items from the vertical block (name + qty + unit price)
        assertEquals(2, p.items().size());
        OrderItem biryani = p.items().get(0);
        assertEquals("Veg Biryani", biryani.getName());
        assertEquals(1, biryani.getQty());
        assertMoney("230", biryani.getPrice());
        OrderItem shake = p.items().get(1);
        assertEquals("Butter Scotch Shake", shake.getName());
        assertEquals(1, shake.getQty());
        assertMoney("190", shake.getPrice());

        // bill breakdown ("(+)"/"(-)" sign-prefixed lines) + grand total
        assertMoney("420", p.subtotalAmount());          // Base Price Total
        assertMoney("23.08", p.gstAmount());             // 18.5 (food) + 4.58 (delivery)
        assertMoney("25.42", p.deliveryFee());           // Delivery Charge
        assertMoney("50", p.discountAmount());           // Discount
        assertMoney("459", p.amount());                  // Order Total
    }
}

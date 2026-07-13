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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance test for {@link RelfoodEmailParser} against the REAL RELFOOD order
 * email as the extractor now hands it over: one field/value per line, with the
 * item + bill numbers glued onto their labels/description ("Sub Total194",
 * "GST10", "Total204", "…Cutlery1941194"). Asserts the actual values in
 * {@code src/test/resources/agg/RELFOOD.txt}.
 */
class RelfoodEmailParserTest {

    private final RelfoodEmailParser relfood = new RelfoodEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static String load(String name) {
        try (InputStream in = RelfoodEmailParserTest.class.getResourceAsStream("/agg/" + name)) {
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
    void parsesRealRelfoodNewlinePerFieldLayout() {
        String body = load("RELFOOD.txt");
        ParsedOrder p = relfood.parse(agg("RELFOOD"), "orders@relfood.com",
                "REL FOOD Order Confirmation", body, "<real-relfood@relfood.com>");

        // Header fields (the real IRCTC order no. — NOT the REL FOOD ref 1092200).
        assertEquals("2465463246", p.externalOrderId());
        assertEquals("VIVEK CHANDEL", p.passengerName());
        assertEquals("7587222294", p.passengerPhone());
        assertNull(p.pnr()); // masked "XXXXXXXXXX"

        // Train: 5-digit number only, name separate.
        assertEquals("12410", p.trainNumber());
        assertEquals("GONDWANA EXP", p.trainName());

        // Coach / seat.
        assertEquals("M2", p.coach());
        assertEquals("66", p.berth());

        // Payment.
        assertEquals("COD", p.paymentMode());
        assertMoney("204", p.amountToCollect());

        // Delivery station (name + code) and date/slot.
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("20:55", p.deliverySlot());

        // Line item: glued "1941194" → price 194, qty 1, total 194.
        assertEquals(1, p.items().size());
        OrderItem item = p.items().get(0);
        assertTrue(item.getName().startsWith("Veg Biryani"),
                "item name was: " + item.getName());
        assertEquals(1, item.getQty());
        assertMoney("194", item.getPrice());

        // Bill breakdown + grand total.
        assertMoney("194", p.subtotalAmount());
        assertMoney("0.00", p.deliveryFee());
        assertMoney("10", p.gstAmount());
        assertMoney("204", p.amount());
    }
}

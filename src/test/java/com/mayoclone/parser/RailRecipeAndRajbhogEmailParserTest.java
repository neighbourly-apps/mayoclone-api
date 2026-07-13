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
 * Acceptance tests for the dedicated {@link RailRecipeEmailParser} and
 * {@link RajbhogEmailParser} against the REAL, verbatim decoded emails these two
 * aggregators send now (each field/value on its own line). Fixtures live at
 * {@code src/test/resources/agg/RAILRECIPE.txt} and {@code .../RAJBHOGKHANA.txt};
 * the assertions are the actual values in those files.
 */
class RailRecipeAndRajbhogEmailParserTest {

    private final RailRecipeEmailParser railRecipe = new RailRecipeEmailParser();
    private final RajbhogEmailParser rajbhog = new RajbhogEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static String load(String name) {
        try (InputStream in = RailRecipeAndRajbhogEmailParserTest.class
                .getResourceAsStream("/agg/" + name)) {
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

    // -- RailRecipe — real order 1826362 -------------------------------------
    @Test
    void supportsOnlyRailRecipe() {
        assertTrue(railRecipe.supports(agg("RAILRECIPE"), "x", "y"));
        assertTrue(railRecipe.supports(agg("railrecipe"), "x", "y"));
        assertTrue(!railRecipe.supports(agg("RAJBHOGKHANA"), "x", "y"));
        assertTrue(!railRecipe.supports(agg("ZOOP"), "x", "y"));
    }

    @Test
    void parsesRealRailRecipe() {
        String body = load("RAILRECIPE.txt");
        ParsedOrder p = railRecipe.parse(agg("RAILRECIPE"), "no-reply@railrecipe.com",
                "RailRecipe Order Confirmation", body, "<real-railrecipe@railrecipe.com>");

        assertEquals("1826362", p.externalOrderId());
        assertEquals("2953991789", p.pnr());
        assertEquals("12190", p.trainNumber());
        assertNull(p.trainName());
        assertEquals("M1", p.coach());
        assertEquals("74", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        // phone-only email: no labelled customer/passenger name → default.
        assertEquals("Unknown", p.passengerName());
        assertEquals("8850989379", p.passengerPhone());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("19:30", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        assertEquals(2, p.items().size());
        OrderItem a = p.items().get(0);
        assertEquals("Paneer pizza", a.getName());
        assertEquals(1, a.getQty());
        assertMoney("165", a.getPrice());
        OrderItem b = p.items().get(1);
        assertEquals("Paneer onion capsicum pizza", b.getName());
        assertEquals(1, b.getQty());
        assertMoney("165", b.getPrice());

        assertMoney("330", p.subtotalAmount());
        assertMoney("16.50", p.gstAmount());
        assertMoney("0", p.deliveryFee());
        assertMoney("0", p.discountAmount());
        assertMoney("346.50", p.amount());
    }

    // -- Rajbhog Khana — real invoice RBK001734622 ---------------------------
    @Test
    void supportsOnlyRajbhog() {
        assertTrue(rajbhog.supports(agg("RAJBHOGKHANA"), "x", "y"));
        assertTrue(rajbhog.supports(agg("rajbhogkhana"), "x", "y"));
        assertTrue(!rajbhog.supports(agg("RAILRECIPE"), "x", "y"));
        assertTrue(!rajbhog.supports(agg("ZOOP"), "x", "y"));
    }

    @Test
    void parsesRealRajbhog() {
        String body = load("RAJBHOGKHANA.txt");
        ParsedOrder p = rajbhog.parse(agg("RAJBHOGKHANA"), "orders@rajbhogkhana.com",
                "Rajbhog Order Invoice", body, "<real-rajbhog@rajbhogkhana.com>");

        // The real id is the RBK… invoice number — never the stray "Booking".
        assertEquals("RBK001734622", p.externalOrderId());
        assertEquals("Sandeep Gautam", p.passengerName());
        assertEquals("8765962487", p.passengerPhone());
        assertNull(p.pnr());
        assertEquals("22538", p.trainNumber());
        assertEquals("KUSHINAGAR EXP", p.trainName());
        assertEquals("S1", p.coach());
        assertEquals("48", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 11), p.deliveryDate());
        assertEquals("19:15", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        assertEquals(1, p.items().size());
        OrderItem item = p.items().get(0);
        assertEquals("VEG THALI", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("159.00", item.getPrice());

        assertMoney("159.00", p.subtotalAmount());
        assertMoney("7.55", p.gstAmount());
        assertMoney("7.95", p.discountAmount());
        assertMoney("0", p.deliveryFee());
        assertMoney("159.00", p.amount());
    }
}

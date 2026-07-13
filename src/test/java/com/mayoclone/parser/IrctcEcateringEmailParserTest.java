package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance test for {@link IrctcEcateringEmailParser} against the REAL,
 * verbatim IRCTC eCatering vendor order email (copied to
 * {@code src/test/resources/agg/IRCTC_ECATERING.txt}). It asserts the actual
 * values present in that fixture end-to-end.
 */
class IrctcEcateringEmailParserTest {

    private final IrctcEcateringEmailParser parser = new IrctcEcateringEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static String realEmail() {
        try (var in = IrctcEcateringEmailParserTest.class
                .getResourceAsStream("/agg/IRCTC_ECATERING.txt")) {
            if (in == null) {
                throw new IllegalStateException("missing test resource /agg/IRCTC_ECATERING.txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void supportsOnlyIrctcEcatering() {
        assertTrue(parser.supports(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in", "New order"));
        assertTrue(!parser.supports(agg("ZOOP"), "x", "y"));
        assertTrue(!parser.supports(agg("RAILRESTRO"), "x", "y"));
        assertTrue(!parser.supports(null, "x", "y"));
    }

    @Test
    void parsesRealIrctcEcateringEmail() {
        ParsedOrder p = parser.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "New IRCTC order # 2465449929", realEmail(), "<irctc-ecatering-1@irctc.co.in>");

        // Identity ---------------------------------------------------------
        assertEquals("IRCTC_ECATERING", p.aggregatorCode());
        assertEquals("2465449929", p.externalOrderId());  // digits after "ORDER No", never a word
        assertNull(p.pnr());                              // "PNR No  -" → no 10-digit value

        // Train — the 5-digit TRAIN No value ONLY, no name in this format ---
        assertEquals("12190", p.trainNumber());
        assertNull(p.trainName());

        // Coach / seat from "COACH NO / SEAT NO  B5/ 64" -------------------
        assertEquals("B5", p.coach());
        assertEquals("64", p.berth());

        // Delivery station -------------------------------------------------
        assertNull(p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());

        // Passenger: none in this format; phone from "MOBILE No" -----------
        assertEquals("Unknown", p.passengerName());
        assertEquals("8860560770", p.passengerPhone());

        // Journey date / delivery time / payment ---------------------------
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("19:30", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        // Line items (name → ₹price → qty → ₹amount, summary rows skipped) --
        List<OrderItem> items = p.items();
        assertEquals(2, items.size());
        assertEquals("Veg Thali", items.get(0).getName());
        assertEquals(1, items.get(0).getQty());
        assertMoney("114.32", items.get(0).getPrice());
        assertEquals("Curd", items.get(1).getName());
        assertEquals(1, items.get(1).getQty());
        assertMoney("57.14", items.get(1).getPrice());

        // Bill breakdown ---------------------------------------------------
        assertMoney("171.42", p.subtotalAmount());   // Sub Total
        assertMoney("8.58", p.gstAmount());           // GST
        assertMoney("0", p.deliveryFee());            // Delivery Charge
        assertNull(p.discountAmount());               // "Discount*  ₹" carries no number
        assertMoney("180", p.amount());               // Grand Total (Inclusive of all taxes)
        assertEquals("INR", p.currency());
    }
}

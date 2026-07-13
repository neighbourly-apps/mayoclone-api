package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance tests against the REAL aggregator emails that synced from a live
 * restaurant mailbox. Each fixture in {@code src/test/resources/realmail/} is the
 * verbatim HTML→text body the {@code MimeEmailParser} now hands the parser: every
 * field label, every value and every item cell is on its OWN line (the vertical
 * layout). These assert the actual values in those files — they are the acceptance
 * criteria for the vertical-layout tuning.
 */
class RealDecodedAggregatorEmailParsingTest {

    private final GenericIrctcEmailParser generic = new GenericIrctcEmailParser();
    private final RelfoodEmailParser relfood = new RelfoodEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static String load(String name) {
        try (InputStream in = RealDecodedAggregatorEmailParsingTest.class
                .getResourceAsStream("/realmail/" + name)) {
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

    // 1) IRCTC eCatering — order 2465449929 (decoded_15) --------------------------
    @Test
    void parsesRealIrctc15() {
        String body = load("decoded_15_IRCTC_ECATERING.txt");
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "IRCTC eCatering Order Confirmation", body, "<real-irctc-15@irctc.co.in>");

        assertEquals("2465449929", p.externalOrderId());
        assertNull(p.pnr());
        assertEquals("12190", p.trainNumber());
        assertNull(p.trainName());
        assertEquals("B5", p.coach());
        assertEquals("64", p.berth());
        assertNull(p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals("Unknown", p.passengerName());
        assertEquals("8860560770", p.passengerPhone());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("19:30", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        assertEquals(2, p.items().size());
        OrderItem veg = p.items().get(0);
        assertEquals("Veg Thali", veg.getName());
        assertEquals(1, veg.getQty());
        assertMoney("114.32", veg.getPrice());
        OrderItem curd = p.items().get(1);
        assertEquals("Curd", curd.getName());
        assertEquals(1, curd.getQty());
        assertMoney("57.14", curd.getPrice());

        assertMoney("171.42", p.subtotalAmount());
        assertMoney("8.58", p.gstAmount());
        assertMoney("0", p.deliveryFee());
        assertNull(p.discountAmount());
        assertMoney("180", p.amount());
    }

    // 1b) IRCTC eCatering — order 2465354711 (decoded_12) -------------------------
    @Test
    void parsesRealIrctc12() {
        String body = load("decoded_12_IRCTC_ECATERING.txt");
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "IRCTC eCatering Order Confirmation", body, "<real-irctc-12@irctc.co.in>");

        assertEquals("2465354711", p.externalOrderId());
        assertEquals("12286", p.trainNumber());
        assertEquals("B6", p.coach());
        assertEquals("30", p.berth());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals("9818947552", p.passengerPhone());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("20:45", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        assertEquals(1, p.items().size());
        assertEquals("Non Veg Thali", p.items().get(0).getName());
        assertEquals(1, p.items().get(0).getQty());
        assertMoney("161.9", p.items().get(0).getPrice());

        assertMoney("161.9", p.subtotalAmount());
        assertMoney("8.1", p.gstAmount());
        assertMoney("0", p.deliveryFee());
        assertMoney("170", p.amount());
    }

    // 2) RailRecipe — order 1826362 (decoded_14) ---------------------------------
    @Test
    void parsesRealRailRecipe() {
        String body = load("decoded_14_RAILRECIPE.txt");
        ParsedOrder p = generic.parse(agg("RAILRECIPE"), "no-reply@railrecipe.com",
                "RailRecipe Order Confirmation", body, "<real-railrecipe-14@railrecipe.com>");

        assertEquals("1826362", p.externalOrderId());
        assertEquals("2953991789", p.pnr());
        assertEquals("8850989379", p.passengerPhone());
        assertEquals("12190", p.trainNumber());
        assertEquals("M1", p.coach());
        assertEquals("74", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("19:30", p.deliverySlot());
        assertEquals("COD", p.paymentMode());

        assertEquals(2, p.items().size());
        assertEquals("Paneer pizza", p.items().get(0).getName());
        assertEquals(1, p.items().get(0).getQty());
        assertMoney("165", p.items().get(0).getPrice());
        assertEquals("Paneer onion capsicum pizza", p.items().get(1).getName());
        assertMoney("165", p.items().get(1).getPrice());

        assertMoney("330", p.subtotalAmount());
        assertMoney("16.50", p.gstAmount());
        assertMoney("0", p.deliveryFee());
        assertMoney("0", p.discountAmount());
        assertMoney("346.50", p.amount());
    }

    // 3) RailRestro — order 5622015 (decoded_13) ---------------------------------
    @Test
    void parsesRealRailRestro() {
        String body = load("decoded_13_RAILRESTRO.txt");
        ParsedOrder p = generic.parse(agg("RAILRESTRO"), "no-reply@railrestro.com",
                "New Order #5622015 Received", body, "<real-railrestro-13@railrestro.com>");

        assertEquals("5622015", p.externalOrderId());
        assertEquals("Preema verma M.", p.passengerName());
        assertEquals("7000667221", p.passengerPhone());
        assertEquals("12190", p.trainNumber());
        assertEquals("MAHAKAUSHAL EXP", p.trainName());
        assertEquals("2259312759", p.pnr());
        assertEquals("B1", p.coach());
        assertEquals("27", p.berth());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("19:35", p.deliverySlot());
        assertEquals("PREPAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());

        assertEquals(1, p.items().size());
        assertEquals("Veg Mini Thali", p.items().get(0).getName());
        assertEquals(1, p.items().get(0).getQty());
        assertMoney("145", p.items().get(0).getPrice());

        assertMoney("7.25", p.gstAmount());
        assertMoney("152.25", p.amount());
    }

    // 4) RELFOOD — order 2465443414 (decoded_16) ---------------------------------
    @Test
    void parsesRealRelfood() {
        String body = load("decoded_16_RELFOOD.txt");
        ParsedOrder p = relfood.parse(agg("RELFOOD"), "orders@relfood.com",
                "REL FOOD Order Confirmation", body, "<real-relfood-16@relfood.com>");

        assertEquals("2465443414", p.externalOrderId());
        assertEquals("E R SHEIKH", p.passengerName());
        assertEquals("9425041249", p.passengerPhone());
        assertNull(p.pnr());
        assertEquals("12612", p.trainNumber());
        assertEquals("MAS GARIB RATH", p.trainName());
        assertEquals("G4", p.coach());
        assertEquals("65", p.berth());
        assertEquals("PAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 13), p.deliveryDate());
        assertEquals("20:40", p.deliverySlot());

        assertEquals(1, p.items().size());
        OrderItem item = p.items().get(0);
        assertTrue(item.getName().startsWith("Chicken Biryani"),
                "item name was: " + item.getName());
        assertEquals(1, item.getQty());
        assertMoney("299", item.getPrice());

        assertMoney("299", p.subtotalAmount());
        assertMoney("0.00", p.deliveryFee());
        assertMoney("15", p.gstAmount());
        assertMoney("314", p.amount());
    }

    // 5) IRCTC "delivery time passed" notice (decoded_11) — not a full order.
    // It has no order-item table, so it must parse WITHOUT throwing and surface no
    // items (the ingest pipeline flags such a low-confidence parse for review).
    @Test
    void parsesRealIrctc11NoticeWithoutCrashing() {
        String body = load("decoded_11_IRCTC_ECATERING.txt");
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "Delivery time passed", body, "<real-irctc-11@irctc.co.in>");
        assertTrue(p.items().isEmpty(), "notice email carries no line items");
        // No labelled customer/passenger field (just a "Dear …" greeting) → default.
        assertEquals("Unknown", p.passengerName());
    }
}

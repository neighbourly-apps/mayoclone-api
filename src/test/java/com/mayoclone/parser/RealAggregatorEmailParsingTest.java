package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Acceptance tests: the SIX real IRCTC e-catering aggregator order emails (all to
 * "Zaika Foods") must each parse into a correct {@link ParsedOrder}. Each fixture
 * is the verbatim flattened-HTML body text; column cells are separated by runs of
 * spaces, as they arrive after HTML→text conversion.
 */
class RealAggregatorEmailParsingTest {

    private final GenericIrctcEmailParser generic = new GenericIrctcEmailParser();
    private final ZoopEmailParser zoop = new ZoopEmailParser();

    private static Aggregator agg(String code) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(code);
        return a;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    // 1) IRCTC eCatering ------------------------------------------------------
    private static final String IRCTC = """
            Order Confirmation
            Dear Zaika Foods,
            We have received the COD Order having Order Number 2465161370 on 12-07-2026
            Order details:
            ORDER No    2465161370    PNR No    -
            MOBILE No    9423658609    TRAIN No    16032
            ALTERNATE MOBILE No    -    Comment    -
            JOURNEY DATE    12-07-2026    ORDER DATE    12-07-2026
            PAYMENT STATUS    CASH_ON_DELIVERY    COACH NO / SEAT NO    RAC/B3/ 47
            DELIVERY STATION    VIRANGANA LAKSHMIBAI JHANSI JN    DELIVERY TIME    21:55
            VENDOR    Zaika Foods    ETA    12-Jul-2026 21:55
            Order Item Details:
            Item    Price    Quantity    Amount
            Chicken Dum Biryani    ₹ 190.48    1    ₹ 190.48
            Sub Total    ₹ 190.48
            GST    ₹ 9.52
            Delivery Charge    ₹ 0
            Discount*    ₹
            Grand Total (Inclusive of all taxes)    ₹ 200
            """;

    @Test
    void parsesIrctcEcatering() {
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "New IRCTC order # 2465161370", IRCTC, "<irctc-1@irctc.co.in>");

        assertEquals("2465161370", p.externalOrderId());
        assertNull(p.pnr());
        assertEquals("16032", p.trainNumber());
        assertNull(p.trainName());
        assertEquals("B3", p.coach());
        assertEquals("47", p.berth());
        assertNull(p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals("Unknown", p.passengerName());
        assertEquals("9423658609", p.passengerPhone());
        assertEquals(LocalDate.of(2026, 7, 12), p.deliveryDate());
        assertEquals("21:55", p.deliverySlot());
        assertEquals("COD", p.paymentMode());
        OrderItem item = p.items().get(0);
        assertEquals("Chicken Dum Biryani", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("190.48", item.getPrice());
        assertMoney("200", p.amount());
    }

    // 2) RailRestro -----------------------------------------------------------
    private static final String RAILRESTRO = """
            Dear ROHAN AHMAD KHAN,
            You have just received a new order, Please ensure delivery on the journey date:
            ORDER #: 5618572 Customer: Haidar Ali M. 7355650315
            TRAIN: 22538 / KUSHINAGAR EXP
            Delivery Time: 2026-07-12 20:00:00
            PNR No.: 8830095915 Coact/Seat: B6-32
            Item Name    Price    Quantity    Total
            Veg Maharaja Thali    Rs. 240    1    Rs. 240
            Total:    Rs. 240
            GST:    Rs. 12
            Subtotal:    Rs. 252
            Extra Charges:    Rs. 0
            Cashback:    Rs. 0.00
            Payable Total:    Rs. 252
            (Amount to collect)    Rs. 252
            """;

    @Test
    void parsesRailRestro() {
        ParsedOrder p = generic.parse(agg("RAILRESTRO"), "no-reply@railrestro.com",
                "New Order #5618572 Received", RAILRESTRO, "<rr-1@railrestro.com>");

        assertEquals("5618572", p.externalOrderId());
        assertEquals("Haidar Ali M.", p.passengerName());
        assertEquals("7355650315", p.passengerPhone());
        assertEquals("22538", p.trainNumber());
        assertEquals("KUSHINAGAR EXP", p.trainName());
        assertEquals(LocalDate.of(2026, 7, 12), p.deliveryDate());
        assertEquals("20:00", p.deliverySlot());
        assertEquals("8830095915", p.pnr());
        assertEquals("B6", p.coach());
        assertEquals("32", p.berth());
        assertNull(p.deliveryStationCode());
        assertNull(p.deliveryStationName());
        OrderItem item = p.items().get(0);
        assertEquals("Veg Maharaja Thali", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("252", p.amount());
        assertMoney("252", p.amountToCollect());
        assertEquals("COD", p.paymentMode());
    }

    // 3) RELFOOD --------------------------------------------------------------
    private static final String RELFOOD = """
            Virendra Nishad
            IRCTC Order No. 2465035817
            Booking Date : 12-07-2026
            ORDER SUMMERY
            Customer Name    Virendra Nishad
            Contact Number    9380818201, 9380818201
            PNR    XXXXXXXXXX
            Train No./Name    22538 / KUSHINAGAR EXP
            Coach/Seat    S1/54
            Payment Mode    PAID
            Payment to collect    0
            BILL DETAILS
            REL FOOD Ref.No : 1090864
            OUTLET NAME : Zaika Food VGLJ
            Station Name & Code : VIRANGANA LAKSHMIBAI JHANSI JN (VGLJ)
            Delivery Date & Time : 7/12/2026 & 19:26
            Item    Price    Quantity    Total
            Veg Thali
            Paneer Gravy Rice 3 Roti Achar Salad Sweet Mf Spoon Napkin    164    1    164
            Chicken Biryani
            Serve 400 Gram Biryani With Chutney And Raita And Cutlery    299    1    299
            Sub Total    413
            Delivery Fee    0.00
            GST    21
            Total    434
            """;

    @Test
    void parsesRelfood() {
        ParsedOrder p = generic.parse(agg("RELFOOD"), "orders@relfood.com",
                "REL FOOD Order Invoice No.: 1090864", RELFOOD, "<rel-1@relfood.com>");

        assertEquals("2465035817", p.externalOrderId());
        assertEquals("Virendra Nishad", p.passengerName());
        assertEquals("9380818201", p.passengerPhone());
        assertNull(p.pnr());
        assertEquals("22538", p.trainNumber());
        assertEquals("KUSHINAGAR EXP", p.trainName());
        assertEquals("S1", p.coach());
        assertEquals("54", p.berth());
        assertEquals("PAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 12), p.deliveryDate());
        assertEquals("19:26", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("Veg Thali", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("164", item.getPrice());
        assertEquals(2, p.items().size());
        assertEquals("Chicken Biryani", p.items().get(1).getName());
        assertMoney("434", p.amount());
    }

    // 4) RailRecipe -----------------------------------------------------------
    private static final String RAILRECIPE = """
            Dear Zaika Foods ,
            We have received the COD Order having Order Number 1824362 on Jul 12, 2026
            Order Details :
            Order No.    1824362
            PNR No    8648384217
            Mobile No.    9978349731
            Alt. mobile no
            Train No.    19167
            Coach/Seat    B4/43
            Delivery Station    VGLJ
            Delivery Time (ETA)    Jul 12,2026 17:40
            Journey Date    2026-07-11
            Order Date    Jul 12, 2026
            Comment
            PAYMENT STATUS    CASH_ON_DELIVERY
            Item Name Price Quantity Amount
            Aloo Paratha
            Home Style Aalo paratha with Achar
            ₹ 60    x1    ₹60
            Subtotal    ₹ 60
            Discount    ₹ 0
            Delivery Charge    ₹ 0
            GST    ₹ 3.00
            Grand Total    ₹ 63.00
            """;

    @Test
    void parsesRailRecipe() {
        ParsedOrder p = generic.parse(agg("RAILRECIPE"), "no-reply@railrecipe.com",
                "Dear Zaika Foods, order id 1824362 is ACCEPTED", RAILRECIPE, "<rec-1@railrecipe.com>");

        assertEquals("1824362", p.externalOrderId());
        assertEquals("8648384217", p.pnr());
        assertEquals("9978349731", p.passengerPhone());
        assertEquals("Unknown", p.passengerName());
        assertEquals("19167", p.trainNumber());
        assertEquals("B4", p.coach());
        assertEquals("43", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("17:40", p.deliverySlot());
        assertEquals(LocalDate.of(2026, 7, 11), p.deliveryDate());
        assertEquals("COD", p.paymentMode());
        OrderItem item = p.items().get(0);
        assertEquals("Aloo Paratha", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("60", item.getPrice());
        assertMoney("63.00", p.amount());
    }

    // 5) Zoop -----------------------------------------------------------------
    private static final String ZOOP = """
            ZOOP
            Order Confirmation
            Dear Zaika Foods,
            You have received a New Prepaid Order having Order Number ZO119580365463422030 on 12-Jul-2026 13:40
            Order Details:
            ZOOP Txn. No.    : ZO119580365463422030    Type    : Prepaid
            Customer Name    : Anuj Setia    Phone    : 9214362909
            Train    : Kurj Udz Exp/ 19665    Coach/ Seat    : B4/ 6
            Restaurants Name    : (1533) Zaika Foods    ETA    : 12-Jul-2026 13:40
            At    : Virangana Lakshmibai Jhansi Junction/ VGLJ    Delivery Date    : 12-Jul-2026 13:40
            Item Details:
            Item Name    Price    Quantity    Amount
            Veg Biryani    230    2    460
            Base Price Total    ₹ 460
            (+) GST on food    ₹ 20.5
            (+) Delivery Charge    ₹ 25.42
            (-) Discount    ₹ 50
            Order Total    ₹ 461
            (-) Paid Online    ₹ 461
            BALANCE TO PAY    ₹ 0
            """;

    @Test
    void parsesZoop() {
        ParsedOrder p = zoop.parse(agg("ZOOP"), "noreply@zoopindia.com",
                "ZOOP - IRCTC New Order #ZO119580365463422030", ZOOP, "<zoop-1@zoopindia.com>");

        assertEquals("ZO119580365463422030", p.externalOrderId());
        assertEquals("Anuj Setia", p.passengerName());
        assertEquals("9214362909", p.passengerPhone());
        assertEquals("19665", p.trainNumber());
        assertEquals("Kurj Udz Exp", p.trainName());
        assertEquals("B4", p.coach());
        assertEquals("6", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("Virangana Lakshmibai Jhansi Junction", p.deliveryStationName());
        assertEquals("PREPAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());
        assertEquals(LocalDate.of(2026, 7, 12), p.deliveryDate());
        assertEquals("13:40", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("Veg Biryani", item.getName());
        assertEquals(2, item.getQty());
        assertMoney("230", item.getPrice());
        assertMoney("461", p.amount());
    }

    // 6) Rajbhog Khana --------------------------------------------------------
    private static final String RAJBHOG = """
            Booking Date: 11 Jul 2026, 16:21
            Delivery Date: 11 Jul 2026, 19:15
            FSSAI NO.: 22722621000355
            To
            Customer Name : Sandeep Gautam
            Customer Contact : 8765962487
            Customer Email :
            Invoice RBK001734622 / 2464656924
            Payment: CASH_ON_DELIVERY
            Coach / Berth: S1 / 48
            Train: 22538 / KUSHINAGAR EXP
            Delivery Station: VGLJ / VIRANGANA LAKSHMIBAI JHANSI JN
            SL#    Item    Description    Qty    Price    GST    Amount
            1    VEG THALI    paneer gravy , rice , 3 roti , achar , salad , sweet , mouth freshener , spoon , napkin    1    159.00    7.55    159.00
            Subtotal:    159.00
            GST (5%)    7.55
            Discount    7.95
            Delivery:    0
            Total:    159.00
            """;

    @Test
    void parsesRajbhogKhana() {
        ParsedOrder p = generic.parse(agg("RAJBHOGKHANA"), "orders@rajbhogkhana.com",
                "RBK Order Confirmation #RBK001734622", RAJBHOG, "<rbk-1@rajbhogkhana.com>");

        assertEquals("RBK001734622", p.externalOrderId());
        assertEquals("Sandeep Gautam", p.passengerName());
        assertEquals("8765962487", p.passengerPhone());
        assertEquals("COD", p.paymentMode());
        assertEquals("S1", p.coach());
        assertEquals("48", p.berth());
        assertEquals("22538", p.trainNumber());
        assertEquals("KUSHINAGAR EXP", p.trainName());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 11), p.deliveryDate());
        assertEquals("19:15", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("VEG THALI", item.getName());
        assertEquals(1, item.getQty());
        assertMoney("159.00", item.getPrice());
        assertMoney("159.00", p.amount());
    }
}

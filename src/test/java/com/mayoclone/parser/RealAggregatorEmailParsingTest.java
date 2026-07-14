package com.mayoclone.parser;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance tests: the SIX real IRCTC e-catering aggregator order emails (all to
 * "Zaika Foods") must each parse into a correct {@link ParsedOrder}. Each fixture
 * is the verbatim flattened-HTML body text; column cells are separated by runs of
 * spaces, as they arrive after HTML→text conversion.
 */
class RealAggregatorEmailParsingTest {

    private final GenericIrctcEmailParser generic = new GenericIrctcEmailParser();
    private final ZoopEmailParser zoop = new ZoopEmailParser();
    // RELFOOD is genuinely US M/d dates + its own layouts — test the real production parser.
    private final RelfoodEmailParser relfood = new RelfoodEmailParser();
    // Yatri Restro is a fully vertical (label-then-next-line) layout — its own parser.
    private final YatriRestroEmailParser yatri = new YatriRestroEmailParser();
    // RailRestro has inverted bill labels ("Total:" = food subtotal) — its own parser.
    private final RailRestroEmailParser railrestro = new RailRestroEmailParser();

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
        ParsedOrder p = railrestro.parse(agg("RAILRESTRO"), "no-reply@railrestro.com",
                "New Order #5618572 Received", RAILRESTRO, "<rr-1@railrestro.com>");

        assertEquals("5618572", p.externalOrderId());
        // Inverted labels: subtotal is the FOOD "Total:" (240), NOT the GST-inclusive
        // "Subtotal:" (252) — so subtotal + GST (12) reconciles to the grand total (252).
        assertMoney("240", p.subtotalAmount());
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
        ParsedOrder p = relfood.parse(agg("RELFOOD"), "orders@relfood.com",
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
        // The multi-line RELFOOD layout now captures the dish description too.
        assertTrue(item.getDescription() != null && item.getDescription().contains("Paneer Gravy"),
                "expected item description to contain 'Paneer Gravy' but was " + item.getDescription());
        assertEquals(1, item.getQty());
        assertMoney("164", item.getPrice());
        assertEquals(2, p.items().size());
        assertEquals("Chicken Biryani", p.items().get(1).getName());
        assertTrue(p.items().get(1).getDescription() != null
                        && p.items().get(1).getDescription().contains("Biryani"),
                "expected second item description captured but was " + p.items().get(1).getDescription());
        assertMoney("434", p.amount());
    }

    // 3b) RELFOOD GLUED multi-item — the LIVE production shape: dish name on its own line,
    //     then a description line with "<price><qty><total>" welded onto the tail. Two items
    //     must NOT collapse into one, name/description stay separate, prices are per-item.
    private static final String RELFOOD_GLUED = """
            Vishal Patel
            IRCTC Order No. 2465275413
            Customer Name
            Vishal Patel
            Contact Number
            8009549837
            Train No./Name
            12645 / MILLENNIUM EXP
            Coach/Seat
            S1/12
            Payment Mode
            COD
            Payment to collect
            463
            Station Name & Code : VIRANGANA LAKSHMIBAI JHANSI JN (VGLJ)
            Delivery Date & Time : 7/13/2026 & 11:42
            ItemPriceQuantityTotal
            Veg Thali
            Paneer Gravy Rice 3 Roti Achar Salad Sweet Mf Spoon Napkin1641164
            Chicken Biryani
            Serve 400 Gram Biryani With Chutney And Raita And Cutlery2991299
            Sub Total458
            Delivery Fee0.00
            GST5
            Total463
            """;

    @Test
    void parsesRelfoodGluedMultiItem() {
        ParsedOrder p = relfood.parse(agg("RELFOOD"), "orders@relfood.com",
                "REL FOOD Order", RELFOOD_GLUED, "<rel-glued@relfood.com>");

        assertEquals("2465275413", p.externalOrderId());
        assertEquals("12645", p.trainNumber());
        assertEquals("VGLJ", p.deliveryStationCode());
        // TWO distinct items, correct per-item prices, name separated from description.
        assertEquals(2, p.items().size());
        assertEquals("Veg Thali", p.items().get(0).getName());
        assertMoney("164", p.items().get(0).getPrice());
        assertTrue(p.items().get(0).getDescription() != null
                        && p.items().get(0).getDescription().contains("Paneer Gravy"),
                "item0 desc was " + p.items().get(0).getDescription());
        assertEquals("Chicken Biryani", p.items().get(1).getName());
        assertMoney("299", p.items().get(1).getPrice());
        assertTrue(p.items().get(1).getDescription() != null
                        && p.items().get(1).getDescription().contains("Cutlery"),
                "item1 desc was " + p.items().get(1).getDescription());
        assertMoney("463", p.amount());
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
            (+) GST on Delivery Charge    ₹ 4.58
            (+) Gateway Platform Fees    ₹ 30
            (-) Discount    ₹ 50
            Order Total    ₹ 491
            (-) Paid Online    ₹ 491
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
        assertMoney("491", p.amount());
        // The uncategorised extra fee (Gateway Platform Fees) is captured as a named charge;
        // "GST on Delivery Charge" is NOT (it's already summed into gstAmount) → no double-count.
        assertEquals(1, p.charges().size());
        assertEquals("Gateway Platform Fees", p.charges().get(0).getLabel());
        assertMoney("30", p.charges().get(0).getAmount());
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

    // 7) Yatri Restro — the REAL email flattens to a fully VERTICAL layout: every field is a
    //    label on one line and its value on the NEXT line, and the item description wraps
    //    across lines. Parsed by the dedicated YatriRestroEmailParser.
    private static final String YATRI = """
            My Web Page
            Order Confirmation
            Dear Partner,
            Please prepare order and deliver order on time.
            Order details:
            ORDER No
            1000525873
            MOBILE NO
            7869228585

            CUSTOMER NAME
            Yankeshwar Dhiwar
            TRAIN No /NAME
            12808 / SAMTA EXPRESS

            DELIVERY DATE
            14-07-2026, 13:15
            COACH/BERTH
            B4 / 57

            PAYMENT STATUS
            CASH_ON_DELIVERY
            Station Code/Name
            VGLJ / VIRANGANA LAKSHMIBAI JHANSI JN

            Order Item Details:
            Item
            Description
            Price
            Quantity
            Amount

            Veg Thali
            Paneer Gravy Rice 3 Roti Achar Salad Sweet MF spoon
            Napkin
            ₹ 165
            2
            ₹ 330

            Sub Total
            ₹ 330
            GST
            ₹ 16.50
            DISCOUNT
            ₹ 0
            Grand Total (Inclusive of all taxes)
            ₹ 347
            Warm Regards,
            YATRI RESTRO
            """;

    @Test
    void parsesYatriRestro() {
        ParsedOrder p = yatri.parse(agg("YATRI_RESTRO"), "support@yatrirestro.com",
                "Order From Yatri Restro", YATRI, "<yatri-1@yatrirestro.com>");

        assertEquals("1000525873", p.externalOrderId());
        assertEquals("Yankeshwar Dhiwar", p.passengerName());
        assertEquals("7869228585", p.passengerPhone());
        assertEquals("12808", p.trainNumber());
        assertEquals("SAMTA EXPRESS", p.trainName());
        assertEquals("B4", p.coach());
        assertEquals("57", p.berth());
        assertEquals("VGLJ", p.deliveryStationCode());
        assertEquals("VIRANGANA LAKSHMIBAI JHANSI JN", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 7, 14), p.deliveryDate());
        assertEquals("13:15", p.deliverySlot());
        assertEquals("COD", p.paymentMode());
        assertEquals(1, p.items().size());
        OrderItem item = p.items().get(0);
        assertEquals("Veg Thali", item.getName());
        assertEquals("Paneer Gravy Rice 3 Roti Achar Salad Sweet MF spoon Napkin", item.getDescription());
        assertEquals(2, item.getQty());
        assertMoney("165", item.getPrice());
        assertMoney("330", p.subtotalAmount());
        assertMoney("16.50", p.gstAmount());
        assertMoney("0", p.discountAmount());
        assertMoney("347", p.amount());
    }

    // ========================================================================
    // VALUE-VARIED twins: same six formats, DIFFERENT values throughout, to
    // prove the parser extracts whatever is present rather than memorizing the
    // sample values. Each varies order#, pnr, train, coach, seat, station,
    // passenger, date, item name, qty (>1) and amount; station coverage spans a
    // name-only ("NEW DELHI") and a code-only ("NDLS") value.
    // ========================================================================

    // 1) IRCTC — name-only station "NEW DELHI", qty 3, class prefix "GN".
    private static final String IRCTC_V = """
            Order Confirmation
            Dear Zaika Foods,
            We have received the COD Order having Order Number 9911223344 on 05-08-2026
            Order details:
            ORDER No    9911223344    PNR No    4455667788
            MOBILE No    9812300011    TRAIN No    12951
            ALTERNATE MOBILE No    -    Comment    -
            JOURNEY DATE    05-08-2026    ORDER DATE    05-08-2026
            PAYMENT STATUS    CASH_ON_DELIVERY    COACH NO / SEAT NO    GN/A1/ 12
            DELIVERY STATION    NEW DELHI    DELIVERY TIME    08:30
            VENDOR    Zaika Foods    ETA    05-Aug-2026 08:30
            Order Item Details:
            Item    Price    Quantity    Amount
            Paneer Butter Masala    ₹ 250.00    3    ₹ 750.00
            Sub Total    ₹ 750.00
            GST    ₹ 37.50
            Grand Total (Inclusive of all taxes)    ₹ 812
            """;

    @Test
    void parsesIrctcEcateringVaried() {
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "New IRCTC order # 9911223344", IRCTC_V, "<irctc-2@irctc.co.in>");

        assertEquals("9911223344", p.externalOrderId());
        assertEquals("4455667788", p.pnr());
        assertEquals("9812300011", p.passengerPhone());
        assertEquals("12951", p.trainNumber());
        assertEquals("A1", p.coach());
        assertEquals("12", p.berth());
        assertNull(p.deliveryStationCode());
        assertEquals("NEW DELHI", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 8, 5), p.deliveryDate());
        assertEquals("08:30", p.deliverySlot());
        assertEquals("COD", p.paymentMode());
        OrderItem item = p.items().get(0);
        assertEquals("Paneer Butter Masala", item.getName());
        assertEquals(3, item.getQty());
        assertMoney("250.00", item.getPrice());
        assertMoney("812", p.amount());
    }

    // 2) RailRestro — qty 2, different train/coach/passenger.
    private static final String RAILRESTRO_V = """
            Dear PRIYA SHARMA,
            You have just received a new order, Please ensure delivery on the journey date:
            ORDER #: 7788991 Customer: Ravi Kumar Verma 9001234567
            TRAIN: 12002 / BHOPAL SHATABDI
            Delivery Time: 2026-08-05 09:15:00
            PNR No.: 2233445566 Coact/Seat: A2-15
            Item Name    Price    Quantity    Total
            Chicken Biryani    Rs. 180    2    Rs. 360
            Total:    Rs. 360
            GST:    Rs. 18
            Payable Total:    Rs. 378
            (Amount to collect)    Rs. 378
            """;

    @Test
    void parsesRailRestroVaried() {
        ParsedOrder p = generic.parse(agg("RAILRESTRO"), "no-reply@railrestro.com",
                "New Order #7788991 Received", RAILRESTRO_V, "<rr-2@railrestro.com>");

        assertEquals("7788991", p.externalOrderId());
        assertEquals("Ravi Kumar Verma", p.passengerName());
        assertEquals("9001234567", p.passengerPhone());
        assertEquals("12002", p.trainNumber());
        assertEquals("BHOPAL SHATABDI", p.trainName());
        assertEquals(LocalDate.of(2026, 8, 5), p.deliveryDate());
        assertEquals("09:15", p.deliverySlot());
        assertEquals("2233445566", p.pnr());
        assertEquals("A2", p.coach());
        assertEquals("15", p.berth());
        OrderItem item = p.items().get(0);
        assertEquals("Chicken Biryani", item.getName());
        assertEquals(2, item.getQty());
        assertMoney("180", item.getPrice());
        assertMoney("378", p.amount());
        assertMoney("378", p.amountToCollect());
        assertEquals("COD", p.paymentMode());
    }

    // 3) RELFOOD — NAME (CODE) station "NEW DELHI (NDLS)", US slash date, qty 2.
    private static final String RELFOOD_V = """
            Amit Kumar
            IRCTC Order No. 3312456789
            Booking Date : 05-08-2026
            ORDER SUMMERY
            Customer Name    Amit Kumar
            Contact Number    9887766554, 9887766554
            PNR    XXXXXXXXXX
            Train No./Name    12002 / BHOPAL SHATABDI
            Coach/Seat    A2/22
            Payment Mode    PAID
            Payment to collect    0
            BILL DETAILS
            REL FOOD Ref.No : 2211334
            OUTLET NAME : Zaika Food NDLS
            Station Name & Code : NEW DELHI (NDLS)
            Delivery Date & Time : 8/5/2026 & 09:45
            Item    Price    Quantity    Total
            Veg Thali
            Paneer rice roti salad    150    2    300
            Sub Total    300
            GST    15
            Total    315
            """;

    @Test
    void parsesRelfoodVaried() {
        ParsedOrder p = relfood.parse(agg("RELFOOD"), "orders@relfood.com",
                "REL FOOD Order Invoice No.: 2211334", RELFOOD_V, "<rel-2@relfood.com>");

        assertEquals("3312456789", p.externalOrderId());
        assertEquals("Amit Kumar", p.passengerName());
        assertEquals("9887766554", p.passengerPhone());
        assertNull(p.pnr());
        assertEquals("12002", p.trainNumber());
        assertEquals("BHOPAL SHATABDI", p.trainName());
        assertEquals("A2", p.coach());
        assertEquals("22", p.berth());
        assertEquals("PAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());
        assertEquals("NDLS", p.deliveryStationCode());
        assertEquals("NEW DELHI", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 8, 5), p.deliveryDate());
        assertEquals("09:45", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("Veg Thali", item.getName());
        assertEquals(2, item.getQty());
        assertMoney("150", item.getPrice());
        assertMoney("315", p.amount());
    }

    // 4) RailRecipe — code-only station "NDLS", name/desc + "₹price xN ₹amount", qty 2.
    private static final String RAILRECIPE_V = """
            Dear Zaika Foods ,
            We have received the COD Order having Order Number 5566778 on Aug 5, 2026
            Order Details :
            Order No.    5566778
            PNR No    9911002233
            Mobile No.    9765432100
            Alt. mobile no
            Train No.    22691
            Coach/Seat    C3/8
            Delivery Station    NDLS
            Delivery Time (ETA)    Aug 5,2026 10:20
            Journey Date    2026-08-04
            Order Date    Aug 5, 2026
            Comment
            PAYMENT STATUS    CASH_ON_DELIVERY
            Item Name Price Quantity Amount
            Masala Dosa
            Crispy dosa with sambar
            ₹ 90    x2    ₹180
            Subtotal    ₹ 180
            GST    ₹ 9.00
            Grand Total    ₹ 189.00
            """;

    @Test
    void parsesRailRecipeVaried() {
        ParsedOrder p = generic.parse(agg("RAILRECIPE"), "no-reply@railrecipe.com",
                "Dear Zaika Foods, order id 5566778 is ACCEPTED", RAILRECIPE_V, "<rec-2@railrecipe.com>");

        assertEquals("5566778", p.externalOrderId());
        assertEquals("9911002233", p.pnr());
        assertEquals("9765432100", p.passengerPhone());
        assertEquals("22691", p.trainNumber());
        assertEquals("C3", p.coach());
        assertEquals("8", p.berth());
        assertEquals("NDLS", p.deliveryStationCode());
        assertNull(p.deliveryStationName());
        assertEquals("10:20", p.deliverySlot());
        assertEquals(LocalDate.of(2026, 8, 4), p.deliveryDate());
        assertEquals("COD", p.paymentMode());
        OrderItem item = p.items().get(0);
        assertEquals("Masala Dosa", item.getName());
        assertEquals(2, item.getQty());
        assertMoney("90", item.getPrice());
        assertMoney("189.00", p.amount());
    }

    // 5) Zoop — name-first train "Goa Express/ 12779", At name/ code, qty 3.
    private static final String ZOOP_V = """
            ZOOP
            Order Confirmation
            Dear Zaika Foods,
            You have received a New Prepaid Order having Order Number ZO220445566778899 on 05-Aug-2026 11:10
            Order Details:
            ZOOP Txn. No.    : ZO220445566778899    Type    : Prepaid
            Customer Name    : Meera Nair    Phone    : 9445566778
            Train    : Goa Express/ 12779    Coach/ Seat    : S4/ 21
            Restaurants Name    : (1720) Zaika Foods    ETA    : 05-Aug-2026 11:10
            At    : New Delhi Railway Station/ NDLS    Delivery Date    : 05-Aug-2026 11:10
            Item Details:
            Item Name    Price    Quantity    Amount
            Chicken Curry    180    3    540
            Base Price Total    ₹ 540
            (+) GST on food    ₹ 27
            Order Total    ₹ 567
            (-) Paid Online    ₹ 567
            BALANCE TO PAY    ₹ 0
            """;

    @Test
    void parsesZoopVaried() {
        ParsedOrder p = zoop.parse(agg("ZOOP"), "noreply@zoopindia.com",
                "ZOOP - IRCTC New Order #ZO220445566778899", ZOOP_V, "<zoop-2@zoopindia.com>");

        assertEquals("ZO220445566778899", p.externalOrderId());
        assertEquals("Meera Nair", p.passengerName());
        assertEquals("9445566778", p.passengerPhone());
        assertEquals("12779", p.trainNumber());
        assertEquals("Goa Express", p.trainName());
        assertEquals("S4", p.coach());
        assertEquals("21", p.berth());
        assertEquals("NDLS", p.deliveryStationCode());
        assertEquals("New Delhi Railway Station", p.deliveryStationName());
        assertEquals("PREPAID", p.paymentMode());
        assertMoney("0", p.amountToCollect());
        assertEquals(LocalDate.of(2026, 8, 5), p.deliveryDate());
        assertEquals("11:10", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("Chicken Curry", item.getName());
        assertEquals(3, item.getQty());
        assertMoney("180", item.getPrice());
        assertMoney("567", p.amount());
    }

    // 6) Rajbhog — CODE / NAME station "NDLS / NEW DELHI", SL# column with qty 2.
    private static final String RAJBHOG_V = """
            Booking Date: 04 Aug 2026, 14:00
            Delivery Date: 04 Aug 2026, 18:45
            FSSAI NO.: 11122233344455
            To
            Customer Name : Karan Mehta
            Customer Contact : 9332211445
            Customer Email :
            Invoice RBK009988776 / 3312009988
            Payment: CASH_ON_DELIVERY
            Coach / Berth: B2 / 27
            Train: 12780 / GOA EXPRESS
            Delivery Station: NDLS / NEW DELHI
            SL#    Item    Description    Qty    Price    GST    Amount
            1    CHICKEN THALI    rice dal chicken curry salad sweet    2    140.00    14.00    280.00
            Subtotal:    280.00
            GST (5%)    14.00
            Total:    280.00
            """;

    @Test
    void parsesRajbhogKhanaVaried() {
        ParsedOrder p = generic.parse(agg("RAJBHOGKHANA"), "orders@rajbhogkhana.com",
                "RBK Order Confirmation #RBK009988776", RAJBHOG_V, "<rbk-2@rajbhogkhana.com>");

        assertEquals("RBK009988776", p.externalOrderId());
        assertEquals("Karan Mehta", p.passengerName());
        assertEquals("9332211445", p.passengerPhone());
        assertEquals("COD", p.paymentMode());
        assertEquals("B2", p.coach());
        assertEquals("27", p.berth());
        assertEquals("12780", p.trainNumber());
        assertEquals("GOA EXPRESS", p.trainName());
        assertEquals("NDLS", p.deliveryStationCode());
        assertEquals("NEW DELHI", p.deliveryStationName());
        assertEquals(LocalDate.of(2026, 8, 4), p.deliveryDate());
        assertEquals("18:45", p.deliverySlot());
        OrderItem item = p.items().get(0);
        assertEquals("CHICKEN THALI", item.getName());
        assertEquals(2, item.getQty());
        assertMoney("140.00", item.getPrice());
        assertMoney("280.00", p.amount());
    }

    // Direct repro of the two coordinator bug cases: header-absent columnar with a
    // decoy line; and the "₹price xqty ₹amount" layout with a description line.
    @Test
    void itemParserHandlesDecoyAndHeaderlessColumnar() {
        String body = """
                Chicken Dum Biryani nope
                Paneer Butter Masala    ₹ 250.00    3    ₹ 750.00
                Grand Total (Inclusive of all taxes)    ₹ 812
                """;
        ParsedOrder p = generic.parse(agg("IRCTC_ECATERING"), "ecatering@irctc.co.in",
                "New IRCTC order", body, "<irctc-3@irctc.co.in>");
        assertEquals(1, p.items().size());
        assertEquals("Paneer Butter Masala", p.items().get(0).getName());
        assertEquals(3, p.items().get(0).getQty());
        assertMoney("250.00", p.items().get(0).getPrice());
    }

    @Test
    void itemParserHandlesPriceXQtyAmountWithDescription() {
        String body = """
                Masala Dosa
                Crispy dosa
                ₹ 90    x2    ₹180
                Grand Total    ₹ 145.50
                """;
        ParsedOrder p = generic.parse(agg("RAILRECIPE"), "no-reply@railrecipe.com",
                "order", body, "<rec-3@railrecipe.com>");
        assertEquals(1, p.items().size());
        assertEquals("Masala Dosa", p.items().get(0).getName());
        assertEquals(2, p.items().get(0).getQty());
        assertMoney("90", p.items().get(0).getPrice());
    }
}

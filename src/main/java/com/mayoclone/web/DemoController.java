package com.mayoclone.web;

import com.mayoclone.service.ImapIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feeds a handful of realistic sample IRCTC e-catering emails through the real
 * route -> parse -> dedup -> store pipeline (via
 * {@link ImapIngestionService#ingestRaw}) so the whole flow is demonstrable with
 * no live mailbox and no IMAP.
 *
 * <p>Each sample is sent from a real seeded aggregator's domain (zoopindia.com,
 * railrestro.com, gofoodieonline.com, comesum.com, ecatering.irctc.co.in), so
 * routing by the aggregator table is exercised. Every sample body carries the
 * fields the parser extracts: PNR (10 digits), Train No. (5 digits) + name,
 * delivery station code+name, coach/berth, passenger name+phone, delivery date +
 * time slot, and 1-3 line items with a total.
 *
 * <p>Each invocation uses a monotonic {@link AtomicInteger} to mint FRESH order
 * ids and message ids, so repeated calls keep adding new orders (they are not
 * deduped against each other). Real re-syncs of the same email, by contrast, are
 * deduped and add nothing.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ImapIngestionService ingestionService;
    private final AtomicInteger counter = new AtomicInteger(0);

    public DemoController(ImapIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public Map<String, Integer> ingest() {
        int n = counter.incrementAndGet();
        int newOrders = 0;
        for (Sample s : samples(n)) {
            // Demo orders have no owning vendor (vendorId = null).
            newOrders += ingestionService.ingestRaw(s.from, s.subject, s.body, s.messageId, null).newOrders();
        }
        return Map.of("newOrders", newOrders);
    }

    private List<Sample> samples(int n) {
        String today = LocalDate.now().format(ISO);
        String tomorrow = LocalDate.now().plusDays(1).format(ISO);
        return List.of(
                new Sample(
                        "orders@zoopindia.com",
                        "Zoop order confirmed - ZOOP" + n + "01",
                        """
                        Dear Rajesh Kumar,
                        Your Zoop e-catering order is confirmed.
                        Booking ID: ZOOP%d01
                        PNR: 4512367890
                        Train No.: 12951 Mumbai Rajdhani Express
                        Coach: B3  Berth: 32
                        Boarding: BCT
                        Delivery Station: NDLS New Delhi
                        Passenger: Rajesh Kumar
                        Phone: 9876543210
                        Delivery Date: %s
                        Delivery Time: 13:00-13:30
                        Items:
                        2 x Veg Biryani - ₹180
                        1 x Masala Dosa - ₹90
                        Total: ₹450
                        """.formatted(n, today),
                        "<zoop-%d-01@zoopindia.com>".formatted(n)),
                new Sample(
                        "no-reply@zoopindia.com",
                        "Zoop order confirmed - ZOOP" + n + "02",
                        """
                        Dear Sneha Patil,
                        Your Zoop e-catering order is confirmed.
                        Booking ID: ZOOP%d02
                        PNR: 8823145566
                        Train No.: 12009 Shatabdi Express
                        Coach: C1  Berth: 14
                        Boarding: ADI
                        Delivery Station: BRC Vadodara
                        Passenger: Sneha Patil
                        Phone: 9812233445
                        Delivery Date: %s
                        Delivery Time: 12:30-13:00
                        Items:
                        1 x Gujarati Thali - ₹220
                        2 x Buttermilk - ₹40
                        Total: ₹300
                        """.formatted(n, today),
                        "<zoop-%d-02@zoopindia.com>".formatted(n)),
                new Sample(
                        "orders@railrestro.com",
                        "RailRestro Order #RR" + n + "77 Confirmed",
                        """
                        Hello Amit Sharma,
                        Your RailRestro order is placed.
                        Order ID: RR%d77
                        PNR: 6612349080
                        Train No.: 12303 Poorva Express
                        Coach: S4  Berth: 55
                        Boarding: HWH
                        Delivery Station: ALD Prayagraj Jn
                        Passenger: Amit Sharma
                        Phone: 9900112233
                        Delivery Date: %s
                        Delivery Time: 19:00-19:30
                        3 x Chicken Biryani - ₹200
                        1 x Gulab Jamun - ₹60
                        Total: ₹660
                        """.formatted(n, tomorrow),
                        "<rr-%d-77@railrestro.com>".formatted(n)),
                new Sample(
                        "support@gofoodieonline.com",
                        "Gofoodie order GF" + n + "45 confirmed",
                        """
                        Hi Deepak Nair,
                        Thanks for ordering with Gofoodie.
                        Order No: GF%d45
                        PNR: 4098761234
                        Train No.: 12621 Tamil Nadu Express
                        Coach: A1  Berth: 7
                        Boarding: MAS
                        Delivery Station: BPL Bhopal Jn
                        Passenger: Deepak Nair
                        Phone: 9345612378
                        Delivery Date: %s
                        Delivery Time: 20:15-20:45
                        2 x Paneer Butter Masala - ₹190
                        2 x Butter Naan - ₹40
                        Total: ₹460
                        """.formatted(n, tomorrow),
                        "<gf-%d-45@gofoodieonline.com>".formatted(n)),
                new Sample(
                        "orders@comesum.com",
                        "Comesum order CS" + n + "88 confirmed",
                        """
                        Dear Kavita Reddy,
                        Your Comesum order is confirmed.
                        Order ID: CS%d88
                        PNR: 3311227788
                        Train No.: 12723 Telangana Express
                        Coach: B2  Berth: 21
                        Boarding: NZM
                        Delivery Station: NGP Nagpur
                        Passenger: Kavita Reddy
                        Phone: 9871200034
                        Delivery Date: %s
                        Delivery Time: 14:00-14:30
                        1 x Hyderabadi Biryani - ₹240
                        1 x Coke - ₹40
                        Total: ₹280
                        """.formatted(n, today),
                        "<cs-%d-88@comesum.com>".formatted(n)),
                new Sample(
                        "no-reply@ecatering.irctc.co.in",
                        "IRCTC eCatering Order EC" + n + "555",
                        """
                        Dear Passenger Vikram Singh,
                        Your IRCTC eCatering order is confirmed.
                        Order ID: EC%d555
                        PNR: 7745120099
                        Train No.: 12002 Bhopal Shatabdi
                        Coach: E5  Berth: 63
                        Boarding: NDLS
                        Delivery Station: GWL Gwalior
                        Passenger: Vikram Singh
                        Phone: 9765432109
                        Delivery Date: %s
                        Delivery Time: 10:45-11:15
                        1 x Veg Combo Meal - ₹150
                        1 x Masala Tea - ₹20
                        Total: ₹170
                        """.formatted(n, tomorrow),
                        "<ec-%d-555@ecatering.irctc.co.in>".formatted(n)));
    }

    /** Simple holder for a sample email. */
    private record Sample(String from, String subject, String body, String messageId) {
    }
}

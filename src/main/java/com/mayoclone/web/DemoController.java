package com.mayoclone.web;

import com.mayoclone.service.ImapIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feeds a handful of realistic sample emails through the real
 * parse -> dedup -> store pipeline (via {@link ImapIngestionService#ingestRaw})
 * so the whole flow is demonstrable with no live mailbox.
 *
 * <p>Each invocation uses a monotonic counter to mint FRESH order ids and
 * message ids, so repeated calls keep adding new orders (they are not deduped
 * against each other). The sample bodies are shaped to match exactly what the
 * Zomato/Swiggy/Uber parsers extract (order id, customer, items, total).
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

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
            newOrders += ingestionService.ingestRaw(s.from, s.subject, s.body, s.messageId).newOrders();
        }
        return Map.of("newOrders", newOrders);
    }

    private List<Sample> samples(int n) {
        return List.of(
                new Sample(
                        "orders@zomato.com",
                        "New Order #ZO" + n + "001 on Zomato",
                        """
                        Hi Priya,
                        You have a new order on Zomato.
                        Order ID: ZO%d001
                        Customer: Priya Sharma
                        2 x Paneer Tikka - ₹320
                        1 x Garlic Naan - ₹60
                        Total: ₹700
                        """.formatted(n),
                        "<zo-%d-001@zomato.com>".formatted(n)),
                new Sample(
                        "no-reply@zomato.com",
                        "New Order #ZO" + n + "002 on Zomato",
                        """
                        Hi Rahul,
                        Order ID: ZO%d002
                        Customer: Rahul Verma
                        1 x Chicken Biryani - ₹280
                        2 x Coke - Rs. 60
                        Total: Rs. 400
                        """.formatted(n),
                        "<zo-%d-002@zomato.com>".formatted(n)),
                new Sample(
                        "orders@swiggy.in",
                        "Swiggy order confirmed #SW" + n + "101",
                        """
                        Hi Anjali,
                        A new Swiggy order is here.
                        Order ID: SW%d101
                        Customer: Anjali Nair
                        3 x Masala Dosa - ₹150
                        1 x Filter Coffee - ₹40
                        Total: ₹490
                        """.formatted(n),
                        "<sw-%d-101@swiggy.in>".formatted(n)),
                new Sample(
                        "no-reply@swiggy.in",
                        "Swiggy order confirmed #SW" + n + "102",
                        """
                        Hi Karthik,
                        Order ID: SW%d102
                        Customer: Karthik Iyer
                        2 x Veg Fried Rice - ₹180
                        1 x Gobi Manchurian - ₹160
                        Total: Rs. 520
                        """.formatted(n),
                        "<sw-%d-102@swiggy.in>".formatted(n)),
                new Sample(
                        "receipts@uber.com",
                        "Your Uber Eats order UE" + n + "900",
                        """
                        Hi Meera,
                        Thanks for your Uber Eats order.
                        Order ID: UE%d900
                        Customer: Meera Joshi
                        1 x Margherita Pizza - ₹350
                        1 x Choco Lava Cake - ₹120
                        Total: ₹470
                        """.formatted(n),
                        "<ue-%d-900@uber.com>".formatted(n)));
    }

    /** Simple holder for a sample email. */
    private record Sample(String from, String subject, String body, String messageId) {
    }
}

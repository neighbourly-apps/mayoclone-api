package com.mayoclone.web;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reporting/analytics summary math over MockMvc/H2 with a known seeded dataset. */
class ReportSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-5";
    private static final LocalDate D1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 4, 2);
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 3); // D3 has no orders (zero-fill)

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Autowired
    private AggregatorRepository aggregatorRepo;

    private Long readMyAccountId(String token) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map = json.readValue(me.getResponse().getContentAsString(), Map.class);
        return ((Number) map.get("id")).longValue();
    }

    private void seed(Long accountId, Aggregator agg, PaymentMode pay, OrderStatus status,
                      String amount, LocalDate deliveryDate, String train, String station,
                      Integer deliveryMinutes, List<OrderItem> items) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setAggregator(agg);
        o.setOrderType(agg != null ? OrderType.ONLINE : OrderType.DIRECT);
        o.setPaymentMode(pay);
        o.setStatus(status);
        o.setAmount(new BigDecimal(amount));
        o.setDeliveryDate(deliveryDate);
        o.setTrainNumber(train);
        o.setTrainName("Train " + train);
        o.setDeliveryStationCode(station);
        o.setDeliveryStationName("Station " + station);
        Instant placed = Instant.parse("2026-04-01T08:00:00Z");
        o.setPlacedAt(placed);
        if (status == OrderStatus.DELIVERED && deliveryMinutes != null) {
            o.setDeliveredAt(placed.plus(Duration.ofMinutes(deliveryMinutes)));
        }
        o.setCreatedAt(Instant.now());
        o.setItems(items);
        orderRepo.save(o);
    }

    private static OrderItem item(String name, int qty, String price) {
        return new OrderItem(name, qty, new BigDecimal(price));
    }

    @Test
    void summaryMathAndZeroFill() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);
        Aggregator zoop = aggregatorRepo.findByCodeIgnoreCase("ZOOP").orElseThrow();

        // Day 1
        seed(accountId, zoop, PaymentMode.PREPAID, OrderStatus.DELIVERED, "300", D1, "12951", "NDLS",
                30, List.of(item("Biryani", 3, "100")));
        seed(accountId, zoop, PaymentMode.PREPAID, OrderStatus.DELIVERED, "200", D1, "12951", "NDLS",
                60, List.of(item("Dosa", 1, "100"), item("Biryani", 1, "100")));
        seed(accountId, null, PaymentMode.COD, OrderStatus.UNDELIVERED, "150", D1, "12303", "ALD",
                null, List.of(item("Tea", 3, "50")));
        // Day 2
        seed(accountId, null, PaymentMode.COD, OrderStatus.CANCELLED, "500", D2, "12303", "ALD",
                null, List.of(item("Biryani", 5, "100"))); // excluded from revenue + topItems
        seed(accountId, null, PaymentMode.PREPAID, OrderStatus.NEW, "100", D2, "12951", "NDLS",
                null, List.of(item("Dosa", 1, "100")));

        mvc.perform(get("/api/reports/summary?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // totals: 5 orders, revenue excludes the CANCELLED 500 -> 750
                .andExpect(jsonPath("$.totals.orders").value(5))
                .andExpect(jsonPath("$.totals.revenue").value(750))
                // paymentSplit: online = PREPAID (3 orders, 600), cod = COD (2 orders, 150)
                .andExpect(jsonPath("$.paymentSplit.online.count").value(3))
                .andExpect(jsonPath("$.paymentSplit.online.revenue").value(600))
                .andExpect(jsonPath("$.paymentSplit.cod.count").value(2))
                .andExpect(jsonPath("$.paymentSplit.cod.revenue").value(150))
                // statusCounts (all 8 present)
                .andExpect(jsonPath("$.statusCounts.NEW").value(1))
                .andExpect(jsonPath("$.statusCounts.DELIVERED").value(2))
                .andExpect(jsonPath("$.statusCounts.UNDELIVERED").value(1))
                .andExpect(jsonPath("$.statusCounts.CANCELLED").value(1))
                .andExpect(jsonPath("$.statusCounts.PREPARING").value(0))
                // fulfillmentRate = 2 / (2 + 1) = 0.6666..
                .andExpect(jsonPath("$.fulfillmentRate").value(org.hamcrest.Matchers.closeTo(0.6667, 0.001)))
                // avgDeliveryMinutes = (30 + 60) / 2 = 45
                .andExpect(jsonPath("$.avgDeliveryMinutes").value(45.0))
                // byDay: 3 days, zero-filled last
                .andExpect(jsonPath("$.byDay.length()").value(3))
                .andExpect(jsonPath("$.byDay[0].date").value("2026-04-01"))
                .andExpect(jsonPath("$.byDay[0].orders").value(3))
                .andExpect(jsonPath("$.byDay[0].revenue").value(650))
                .andExpect(jsonPath("$.byDay[1].date").value("2026-04-02"))
                .andExpect(jsonPath("$.byDay[1].orders").value(2))
                .andExpect(jsonPath("$.byDay[1].revenue").value(100))
                .andExpect(jsonPath("$.byDay[2].date").value("2026-04-03"))
                .andExpect(jsonPath("$.byDay[2].orders").value(0))
                .andExpect(jsonPath("$.byDay[2].revenue").value(0))
                // topItems by qty (non-cancelled): Biryani 4 / rev 400, Tea 3 / 150, Dosa 2 / 200
                .andExpect(jsonPath("$.topItems[0].name").value("Biryani"))
                .andExpect(jsonPath("$.topItems[0].qty").value(4))
                .andExpect(jsonPath("$.topItems[0].revenue").value(400))
                .andExpect(jsonPath("$.topItems[1].name").value("Tea"))
                .andExpect(jsonPath("$.topItems[1].qty").value(3))
                .andExpect(jsonPath("$.topItems[2].name").value("Dosa"))
                .andExpect(jsonPath("$.topItems[2].qty").value(2))
                // byAggregator: exactly two buckets — ONLINE ZOOP + a DIRECT bucket
                .andExpect(jsonPath("$.byAggregator.length()").value(2))
                .andExpect(jsonPath("$.byAggregator[*].code",
                        org.hamcrest.Matchers.containsInAnyOrder("ZOOP", "DIRECT")))
                // topTrains present
                .andExpect(jsonPath("$.topTrains.length()").value(2));

        // window echoed back
        mvc.perform(get("/api/reports/summary?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.from").value("2026-04-01"))
                .andExpect(jsonPath("$.to").value("2026-04-03"));
    }

    @Test
    void defaultsToLast30DaysAndIsTenantScoped() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        // No orders seeded for this fresh tenant -> empty-but-well-formed summary.
        mvc.perform(get("/api/reports/summary")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.orders").value(0))
                .andExpect(jsonPath("$.totals.revenue").value(0))
                .andExpect(jsonPath("$.fulfillmentRate").value(0.0))
                .andExpect(jsonPath("$.avgDeliveryMinutes").doesNotExist())
                .andExpect(jsonPath("$.byDay.length()").value(30))
                .andExpect(jsonPath("$.statusCounts.NEW").value(0));
    }
}

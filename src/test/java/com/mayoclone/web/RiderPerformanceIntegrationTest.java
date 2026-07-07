package com.mayoclone.web;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.domain.Rider;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.RiderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rider performance / SLA math + reports/riders array (incl. zero-activity rider). */
class RiderPerformanceIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-perf";
    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);
    private static final LocalDate D = LocalDate.of(2026, 5, 10);

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Autowired
    private RiderRepository riderRepo;

    private Long readMyAccountId(String token) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map = json.readValue(me.getResponse().getContentAsString(), Map.class);
        return ((Number) map.get("id")).longValue();
    }

    private Long rider(Long accountId, String name) {
        Rider r = new Rider();
        r.setAccountId(accountId);
        r.setName(name);
        r.setPhone("9000000000");
        r.setActive(true);
        r.setCreatedAt(Instant.now());
        return riderRepo.save(r).getId();
    }

    private void order(Long accountId, Long riderId, OrderStatus status, LocalDate date,
                       Integer deliveryMinutes) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setOrderType(OrderType.DIRECT);
        o.setPaymentMode(PaymentMode.PREPAID);
        o.setStatus(status);
        o.setAmount(new BigDecimal("100"));
        o.setDeliveryDate(date);
        o.setRiderId(riderId);
        Instant assigned = Instant.parse("2026-05-10T08:00:00Z");
        o.setAssignedAt(assigned);
        if (status == OrderStatus.BILL_PENDING && deliveryMinutes != null) {
            o.setDeliveredAt(assigned.plus(Duration.ofMinutes(deliveryMinutes)));
        }
        o.setPlacedAt(assigned);
        o.setCreatedAt(Instant.now());
        orderRepo.save(o);
    }

    @Test
    void singleRiderPerformanceMath() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);
        Long r = rider(accountId, "Speedy");

        // 2 delivered=BILL_PENDING (20 + 40 min), 1 undelivered=CANCELLED, 1 accepted (assigned but neither)
        order(accountId, r, OrderStatus.BILL_PENDING, D, 20);
        order(accountId, r, OrderStatus.BILL_PENDING, D, 40);
        order(accountId, r, OrderStatus.CANCELLED, D, null);
        order(accountId, r, OrderStatus.ACCEPTED, D, null);

        mvc.perform(get("/api/riders/" + r + "/performance?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riderId").value(r.intValue()))
                .andExpect(jsonPath("$.name").value("Speedy"))
                .andExpect(jsonPath("$.assigned").value(4))
                .andExpect(jsonPath("$.delivered").value(2))
                .andExpect(jsonPath("$.undelivered").value(1))
                // onTimeRate = 2 / (2 + 1) = 0.6667
                .andExpect(jsonPath("$.onTimeRate").value(org.hamcrest.Matchers.closeTo(0.6667, 0.001)))
                // avgDeliveryMinutes = (20 + 40) / 2 = 30
                .andExpect(jsonPath("$.avgDeliveryMinutes").value(30.0));
    }

    @Test
    void crossTenantRiderIs404() throws Exception {
        String tokenA = registerAndToken(uniqueEmail(), PW);
        Long accountA = readMyAccountId(tokenA);
        Long rA = rider(accountA, "A-Rider");

        String tokenB = registerAndToken(uniqueEmail(), PW);
        mvc.perform(get("/api/riders/" + rA + "/performance")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsRidersIncludesZeroActivityRider() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);
        Long active = rider(accountId, "Active");
        Long idle = rider(accountId, "Idle");
        order(accountId, active, OrderStatus.BILL_PENDING, D, 15);

        MvcResult res = mvc.perform(get("/api/reports/riders?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        // idle rider present with zero activity and null rates
        String bodyStr = res.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode arr = json.readTree(bodyStr);
        boolean sawIdle = false;
        boolean sawActive = false;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            if (n.get("riderId").asLong() == idle) {
                sawIdle = true;
                org.junit.jupiter.api.Assertions.assertEquals(0, n.get("assigned").asLong());
                org.junit.jupiter.api.Assertions.assertTrue(n.get("onTimeRate").isNull());
                org.junit.jupiter.api.Assertions.assertTrue(n.get("avgDeliveryMinutes").isNull());
            } else if (n.get("riderId").asLong() == active) {
                sawActive = true;
                org.junit.jupiter.api.Assertions.assertEquals(1, n.get("delivered").asLong());
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(sawIdle && sawActive);
    }
}

package com.mayoclone.web;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report byHour: 24 zero-filled buckets, bucketed on placedAt hour (server zone). */
class ReportByHourIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-hour";
    private static final LocalDate FROM = LocalDate.of(2026, 6, 10);
    private static final LocalDate TO = LocalDate.of(2026, 6, 11);
    private static final LocalDate D = LocalDate.of(2026, 6, 10);

    @Autowired
    private IrctcOrderRepository orderRepo;

    private Long readMyAccountId(String token) throws Exception {
        MvcResult me = mvc.perform(get("/api/auth/me")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> map = json.readValue(me.getResponse().getContentAsString(), Map.class);
        return ((Number) map.get("id")).longValue();
    }

    private void seed(Long accountId, OrderStatus status, Instant placedAt) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setOrderType(OrderType.DIRECT);
        o.setPaymentMode(PaymentMode.PREPAID);
        o.setStatus(status);
        o.setAmount(new BigDecimal("100"));
        o.setDeliveryDate(D);
        o.setPlacedAt(placedAt);
        o.setCreatedAt(Instant.now());
        orderRepo.save(o);
    }

    @Test
    void byHourZeroFillAndBucketing() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);

        // Two non-cancelled at the same instant, one cancelled (excluded from byHour).
        Instant t = Instant.parse("2026-06-10T09:30:00Z");
        seed(accountId, OrderStatus.BILL_PENDING, t);
        seed(accountId, OrderStatus.NEW, t);
        seed(accountId, OrderStatus.CANCELLED, t);

        int expectedHour = t.atZone(ZoneId.systemDefault()).getHour();

        MvcResult res = mvc.perform(get("/api/reports/summary?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byHour.length()").value(24))
                .andExpect(jsonPath("$.byHour[0].hour").value(0))
                .andExpect(jsonPath("$.byHour[23].hour").value(23))
                .andExpect(jsonPath("$.byHour[" + expectedHour + "].orders").value(2))
                .andExpect(jsonPath("$.byHour[" + expectedHour + "].revenue").value(200))
                .andReturn();

        // sum of all byHour orders == 2 (cancelled excluded)
        com.fasterxml.jackson.databind.JsonNode arr =
                json.readTree(res.getResponse().getContentAsString()).get("byHour");
        long total = 0;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            total += n.get("orders").asLong();
        }
        assertEquals(2, total);
    }
}

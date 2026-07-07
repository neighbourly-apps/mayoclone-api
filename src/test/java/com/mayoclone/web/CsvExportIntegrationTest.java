package com.mayoclone.web;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSV export endpoints: content-type, disposition, header + totals rows. */
class CsvExportIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-csv";
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 3);
    private static final LocalDate D = LocalDate.of(2026, 6, 2);

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

    private void seed(Long accountId, Aggregator agg, String amount) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setAggregator(agg);
        o.setOrderType(agg != null ? OrderType.ONLINE : OrderType.DIRECT);
        o.setPaymentMode(PaymentMode.PREPAID);
        o.setStatus(OrderStatus.BILL_PENDING);
        o.setAmount(new BigDecimal(amount));
        o.setDeliveryDate(D);
        o.setPlacedAt(Instant.parse("2026-06-02T09:00:00Z"));
        o.setCreatedAt(Instant.now());
        orderRepo.save(o);
    }

    @Test
    void settlementSummaryCsv() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);
        Aggregator zoop = aggregatorRepo.findByCodeIgnoreCase("ZOOP").orElseThrow();
        seed(accountId, zoop, "1000");

        MvcResult res = mvc.perform(get("/api/settlements/summary.csv?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertTrue(body.startsWith(
                "aggregatorId,aggregatorCode,name,orders,grossAmount,commissionRate,commissionAmount,netPayable"),
                "header row present: " + body);
        assertTrue(body.contains("ZOOP"), "aggregator row present");
        assertTrue(body.contains("TOTAL"), "totals row present");
    }

    @Test
    void reportSummaryCsv() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);
        seed(accountId, null, "250");

        MvcResult res = mvc.perform(get("/api/reports/summary.csv?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertTrue(body.startsWith("date,orders,revenue"), "header row present: " + body);
        // CSV omits empty days: only the seeded 2026-06-02 appears (06-01 / 06-03 dropped).
        assertTrue(body.contains("2026-06-02,1,250"), "the seeded day row present: " + body);
        assertTrue(!body.contains("2026-06-01") && !body.contains("2026-06-03"),
                "empty days omitted from CSV: " + body);
    }
}

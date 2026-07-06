package com.mayoclone.web;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settlement/reconciliation math + persist + mark-settled over MockMvc/H2.
 * Orders are seeded directly through the repositories (there is no API to mint
 * ONLINE/aggregator-attributed orders), attributed to the caller's tenant.
 */
class SettlementIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-4";
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 7);
    private static final LocalDate IN = LocalDate.of(2026, 3, 3);
    private static final LocalDate OUT = LocalDate.of(2026, 2, 1);

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

    private void seed(Long accountId, Aggregator agg, OrderType type, OrderStatus status,
                      String amount, LocalDate deliveryDate) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setAggregator(agg);
        o.setOrderType(type);
        o.setPaymentMode(PaymentMode.PREPAID);
        o.setStatus(status);
        o.setAmount(new BigDecimal(amount));
        o.setDeliveryDate(deliveryDate);
        o.setPlacedAt(Instant.now());
        o.setCreatedAt(Instant.now());
        orderRepo.save(o);
    }

    @Test
    void summaryPersistAndMarkSettled() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long accountId = readMyAccountId(token);

        Aggregator zoop = aggregatorRepo.findByCodeIgnoreCase("ZOOP").orElseThrow();
        Aggregator railrestro = aggregatorRepo.findByCodeIgnoreCase("RAILRESTRO").orElseThrow();
        // Sanity: seeded commission rate is 0.15.
        assertEquals(0, zoop.getCommissionRate().compareTo(new BigDecimal("0.15")));

        // ZOOP: two eligible ONLINE orders (gross 2000) ...
        seed(accountId, zoop, OrderType.ONLINE, OrderStatus.DELIVERED, "1000", IN);
        seed(accountId, zoop, OrderType.ONLINE, OrderStatus.NEW, "1000", IN);
        // ... plus a CANCELLED one (excluded) and an out-of-range one (excluded).
        seed(accountId, zoop, OrderType.ONLINE, OrderStatus.CANCELLED, "500", IN);
        seed(accountId, zoop, OrderType.ONLINE, OrderStatus.DELIVERED, "5000", OUT);
        // DIRECT (null aggregator) is not reconcilable and excluded.
        seed(accountId, null, OrderType.DIRECT, OrderStatus.DELIVERED, "300", IN);
        // RAILRESTRO: one eligible order (gross 1000).
        seed(accountId, railrestro, OrderType.ONLINE, OrderStatus.DELIVERED, "1000", IN);

        // --- summary ---
        MvcResult res = mvc.perform(get("/api/settlements/summary?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.orders").value(3))
                .andReturn();

        JsonNode root = json.readTree(res.getResponse().getContentAsString());
        assertEquals(3000.0, root.at("/totals/grossAmount").asDouble(), 0.001);
        assertEquals(450.0, root.at("/totals/commissionAmount").asDouble(), 0.001);
        assertEquals(2550.0, root.at("/totals/netPayable").asDouble(), 0.001);

        JsonNode zoopRow = rowFor(root, "ZOOP");
        assertNotNull(zoopRow);
        assertEquals(2, zoopRow.get("orders").asLong());
        assertEquals(2000.0, zoopRow.get("grossAmount").asDouble(), 0.001);
        assertEquals(0.15, zoopRow.get("commissionRate").asDouble(), 0.0001);
        assertEquals(300.0, zoopRow.get("commissionAmount").asDouble(), 0.001);
        assertEquals(1700.0, zoopRow.get("netPayable").asDouble(), 0.001);

        JsonNode rrRow = rowFor(root, "RAILRESTRO");
        assertNotNull(rrRow);
        assertEquals(1, rrRow.get("orders").asLong());
        assertEquals(150.0, rrRow.get("commissionAmount").asDouble(), 0.001);
        assertEquals(850.0, rrRow.get("netPayable").asDouble(), 0.001);

        // --- persist a ZOOP snapshot ---
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aggregatorCode", "ZOOP");
        body.put("from", FROM.toString());
        body.put("to", TO.toString());
        MvcResult created = mvc.perform(post("/api/settlements")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aggregatorCode").value("ZOOP"))
                .andExpect(jsonPath("$.aggregatorName").value("Zoop"))
                .andExpect(jsonPath("$.orders").value(2))
                .andExpect(jsonPath("$.grossAmount").value(2000.00))
                .andExpect(jsonPath("$.commissionAmount").value(300.00))
                .andExpect(jsonPath("$.netPayable").value(1700.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.settledAt").doesNotExist())
                .andReturn();
        Long id = ((Number) json.readValue(created.getResponse().getContentAsString(), Map.class)
                .get("id")).longValue();

        // --- list (newest first, tenant-scoped) ---
        mvc.perform(get("/api/settlements")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.intValue()));

        // --- mark settled ---
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "SETTLED");
        patch.put("note", "paid via NEFT");
        mvc.perform(patch("/api/settlements/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.note").value("paid via NEFT"))
                .andExpect(jsonPath("$.settledAt").exists());

        // Cross-tenant PATCH -> 404.
        String tokenB = registerAndToken(uniqueEmail(), PW);
        mvc.perform(patch("/api/settlements/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(patch)))
                .andExpect(status().isNotFound());

        // Invalid status -> 400.
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("status", "BOGUS");
        mvc.perform(patch("/api/settlements/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(bad)))
                .andExpect(status().isBadRequest());
    }

    private static JsonNode rowFor(JsonNode root, String code) {
        for (JsonNode row : root.get("rows")) {
            if (code.equals(row.get("aggregatorCode").asText())) {
                return row;
            }
        }
        return null;
    }

    @Test
    void summaryIsTenantScoped() throws Exception {
        String tokenA = registerAndToken(uniqueEmail(), PW);
        Long accountA = readMyAccountId(tokenA);
        Aggregator zoop = aggregatorRepo.findByCodeIgnoreCase("ZOOP").orElseThrow();
        seed(accountA, zoop, OrderType.ONLINE, OrderStatus.DELIVERED, "1000", IN);

        // A different tenant sees an empty summary for the same window.
        String tokenB = registerAndToken(uniqueEmail(), PW);
        MvcResult res = mvc.perform(get("/api/settlements/summary?from=" + FROM + "&to=" + TO)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.orders").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andReturn();
        assertTrue(res.getResponse().getContentAsString().contains("\"orders\":0"));
    }
}

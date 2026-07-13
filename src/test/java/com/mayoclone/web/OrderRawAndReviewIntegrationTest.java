package com.mayoclone.web;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc/H2 coverage for the lossless-ingestion guarantees exposed on the read side:
 * the full {@code rawEmail} is persisted and served by GET /api/orders/{id}/raw
 * (tenant-scoped), and the orders list filters by {@code needsReview}.
 */
class OrderRawAndReviewIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-3";
    private static final String RAW = """
            Dear Rajesh Kumar,
            Your Zoop e-catering order is confirmed.
            Order ID: ZOOPQ1
            Total: rupees three hundred sixty
            """;

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Autowired
    private AccountRepository accountRepo;

    private Long accountIdFor(String email) {
        return accountRepo.findByEmailIgnoreCase(email).orElseThrow().getId();
    }

    private Long saveOrder(Long accountId, boolean needsReview, String reviewReason) {
        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setOrderType(OrderType.ONLINE);
        o.setPaymentMode(PaymentMode.PREPAID);
        o.setStatus(OrderStatus.NEW);
        o.setCurrency("INR");
        o.setSubject("Zoop order confirmed");
        o.setExternalOrderId("ZOOPQ1-" + System.nanoTime());
        o.setRawEmail(RAW);
        o.setNeedsReview(needsReview);
        o.setReviewReason(reviewReason);
        o.setPlacedAt(Instant.now());
        o.setCreatedAt(Instant.now());
        return orderRepo.save(o).getId();
    }

    @Test
    void rawEndpointReturnsFullEmailAndIsTenantScoped() throws Exception {
        String emailA = uniqueEmail();
        String tokenA = registerAndToken(emailA, PW);
        Long accountA = accountIdFor(emailA);
        Long orderId = saveOrder(accountA, true, "no items parsed");

        mvc.perform(get("/api/orders/" + orderId + "/raw")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.intValue()))
                .andExpect(jsonPath("$.subject").value("Zoop order confirmed"))
                .andExpect(jsonPath("$.rawEmail").value(RAW));

        // Another tenant cannot read the raw source -> 404 (not 403), no existence leak.
        String tokenB = registerAndToken(uniqueEmail(), PW);
        mvc.perform(get("/api/orders/" + orderId + "/raw")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordersListFiltersByNeedsReview() throws Exception {
        String email = uniqueEmail();
        String token = registerAndToken(email, PW);
        Long accountId = accountIdFor(email);
        Long flagged = saveOrder(accountId, true, "amount missing/zero");
        Long clean = saveOrder(accountId, false, null);

        // needsReview=true -> only the flagged order, carrying its reason.
        mvc.perform(get("/api/orders?needsReview=true")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(flagged.intValue()))
                .andExpect(jsonPath("$[0].needsReview").value(true))
                .andExpect(jsonPath("$[0].reviewReason").value("amount missing/zero"));

        // needsReview=false -> only the clean order.
        mvc.perform(get("/api/orders?needsReview=false")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(clean.intValue()))
                .andExpect(jsonPath("$[0].needsReview").value(false));

        // No filter -> both orders returned.
        mvc.perform(get("/api/orders")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}

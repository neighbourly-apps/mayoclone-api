package com.mayoclone.web;

import com.mayoclone.billing.RazorpaySignature;
import com.mayoclone.domain.PendingCheckout;
import com.mayoclone.repository.PendingCheckoutRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack billing flow over MockMvc on H2 (no Docker, no live Razorpay).
 * Uses the {@code test} profile: enforce=false, dev-mode=true, key-id BLANK (so
 * checkout is "not configured"), but key-secret + webhook-secret present so the
 * verify/webhook HMACs can be computed here.
 */
class BillingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    // Must match src/test/resources/application-test.yml.
    private static final String KEY_SECRET = "test-razorpay-key-secret-0123456789";
    private static final String WEBHOOK_SECRET = "test-razorpay-webhook-secret-0123456789";

    @Autowired
    private PendingCheckoutRepository pendingRepo;

    @Test
    void registerStartsTrialAndStatusReportsIt() throws Exception {
        String email = uniqueEmail();
        MvcResult reg = register(email, PASSWORD);
        String token = readAccessToken(reg);
        // AccountDto now carries the trial state.
        Map<?, ?> body = json.readValue(reg.getResponse().getContentAsString(), Map.class);
        Map<?, ?> account = (Map<?, ?>) body.get("account");
        org.junit.jupiter.api.Assertions.assertEquals("TRIALING", account.get("subscriptionStatus"));
        org.junit.jupiter.api.Assertions.assertNotNull(account.get("trialEndsAt"));

        mvc.perform(get("/api/billing/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.trialDaysLeft").value(14))
                .andExpect(jsonPath("$.plan.code").value("pro-monthly"))
                .andExpect(jsonPath("$.plan.amount").value(120000))
                // Both purchasable plans, monthly first, with their ₹1200 / ₹12999 amounts.
                .andExpect(jsonPath("$.plans.length()").value(2))
                .andExpect(jsonPath("$.plans[0].code").value("pro-monthly"))
                .andExpect(jsonPath("$.plans[0].name").value("Monthly"))
                .andExpect(jsonPath("$.plans[0].amount").value(120000))
                .andExpect(jsonPath("$.plans[0].periodDays").value(30))
                .andExpect(jsonPath("$.plans[1].code").value("pro-annual"))
                .andExpect(jsonPath("$.plans[1].name").value("Annual"))
                .andExpect(jsonPath("$.plans[1].amount").value(1299900))
                .andExpect(jsonPath("$.plans[1].periodDays").value(365))
                .andExpect(jsonPath("$.razorpayEnabled").value(false))
                .andExpect(jsonPath("$.devMode").value(true));
    }

    @Test
    void devActivateAnnualSetsPlanAndExtends365Days() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Instant expectedLower = Instant.now().plus(363, ChronoUnit.DAYS);
        Instant expectedUpper = Instant.now().plus(367, ChronoUnit.DAYS);

        MvcResult res = mvc.perform(post("/api/billing/dev-activate")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"plan\":\"pro-annual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.code").value("pro-annual"))
                .andExpect(jsonPath("$.plan.amount").value(1299900))
                .andReturn();

        Map<?, ?> body = json.readValue(res.getResponse().getContentAsString(), Map.class);
        Instant cpe = Instant.parse((String) body.get("currentPeriodEnd"));
        org.junit.jupiter.api.Assertions.assertTrue(
                cpe.isAfter(expectedLower) && cpe.isBefore(expectedUpper),
                "annual currentPeriodEnd should be ~365d out, was " + cpe);
    }

    @Test
    void devActivateMonthlyExtends30Days() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        Instant expectedLower = Instant.now().plus(28, ChronoUnit.DAYS);
        Instant expectedUpper = Instant.now().plus(32, ChronoUnit.DAYS);

        MvcResult res = mvc.perform(post("/api/billing/dev-activate")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"plan\":\"pro-monthly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("pro-monthly"))
                .andReturn();

        Map<?, ?> body = json.readValue(res.getResponse().getContentAsString(), Map.class);
        Instant cpe = Instant.parse((String) body.get("currentPeriodEnd"));
        org.junit.jupiter.api.Assertions.assertTrue(
                cpe.isAfter(expectedLower) && cpe.isBefore(expectedUpper),
                "monthly currentPeriodEnd should be ~30d out, was " + cpe);
    }

    @Test
    void invoicesListsPaymentsAfterActivation() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);

        // No payments yet → empty list.
        mvc.perform(get("/api/billing/invoices")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Pay for the annual plan, then the invoice shows up.
        mvc.perform(post("/api/billing/dev-activate")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"plan\":\"pro-annual\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/billing/invoices")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(1299900))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].description").value("Annual subscription"))
                .andExpect(jsonPath("$[0].number").exists());
    }

    @Test
    void devActivateUnknownPlanIsBadRequest() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        mvc.perform(post("/api/billing/dev-activate")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"plan\":\"bogus-plan\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_plan"));
    }

    @Test
    void checkoutReturns503WhenRazorpayUnconfigured() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        mvc.perform(post("/api/billing/checkout")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("billing_not_configured"));
    }

    @Test
    void verifyWithValidSignatureActivatesAndExtendsPeriod() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        String token = readAccessToken(reg);
        long accountId = accountIdOf(reg);
        String orderId = "order_TEST123";
        String paymentId = "pay_TEST123";
        seedPending(orderId, accountId, "pro-monthly", 120000);
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);

        mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody(orderId, paymentId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.plan.code").value("pro-monthly"))
                .andExpect(jsonPath("$.currentPeriodEnd").isNotEmpty());
    }

    /**
     * FRAUD GUARD: pay for pro-monthly (₹1200) then call /verify with plan=pro-annual.
     * The bound order (pending_checkout) is authoritative → MONTHLY is granted, ~30 days,
     * NOT 365. The client-sent plan is ignored.
     */
    @Test
    void verifyGrantsBoundPlanNotClientSentPlan() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        String token = readAccessToken(reg);
        long accountId = accountIdOf(reg);
        String orderId = "order_MONTHLY_1";
        String paymentId = "pay_MONTHLY_1";
        seedPending(orderId, accountId, "pro-monthly", 120000); // paid for monthly
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);

        Instant monthlyUpper = Instant.now().plus(32, ChronoUnit.DAYS);

        MvcResult res = mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        // client LIES: claims annual
                        .content(verifyBody(orderId, paymentId, signature, "pro-annual")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("pro-monthly")) // bound plan wins
                .andReturn();

        Map<?, ?> body = json.readValue(res.getResponse().getContentAsString(), Map.class);
        Instant cpe = Instant.parse((String) body.get("currentPeriodEnd"));
        org.junit.jupiter.api.Assertions.assertTrue(cpe.isBefore(monthlyUpper),
                "must grant MONTHLY (~30d), not annual (~365d); was " + cpe);

        // The recorded invoice is the MONTHLY amount, not the annual one.
        mvc.perform(get("/api/billing/invoices")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(120000));
    }

    /** A verify for an order owned by ANOTHER account is rejected (400). */
    @Test
    void verifyRejectsOrderOwnedByAnotherAccount() throws Exception {
        MvcResult regA = register(uniqueEmail(), PASSWORD);
        long accountA = accountIdOf(regA);

        String tokenB = registerAndToken(uniqueEmail(), PASSWORD);
        String orderId = "order_OWNED_BY_A";
        String paymentId = "pay_X";
        seedPending(orderId, accountA, "pro-monthly", 120000); // A's order
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);

        // B (a different account) tries to claim A's order.
        mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody(orderId, paymentId, signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_order"));
    }

    /** A verify for an order that was never checked out here is rejected (400). */
    @Test
    void verifyRejectsUnknownOrder() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        String orderId = "order_NEVER_SEEN";
        String paymentId = "pay_Y";
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);

        mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody(orderId, paymentId, signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_order"));
    }

    /** Annual checkout verified normally grants ~365 days. */
    @Test
    void verifyAnnualOrderGrantsAnnual() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        String token = readAccessToken(reg);
        long accountId = accountIdOf(reg);
        String orderId = "order_ANNUAL_1";
        String paymentId = "pay_ANNUAL_1";
        seedPending(orderId, accountId, "pro-annual", 1299900);
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);
        Instant lower = Instant.now().plus(363, ChronoUnit.DAYS);

        MvcResult res = mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody(orderId, paymentId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("pro-annual"))
                .andReturn();
        Map<?, ?> body = json.readValue(res.getResponse().getContentAsString(), Map.class);
        Instant cpe = Instant.parse((String) body.get("currentPeriodEnd"));
        org.junit.jupiter.api.Assertions.assertTrue(cpe.isAfter(lower),
                "annual should extend ~365d; was " + cpe);
    }

    /** A replayed verify (same payment id) stays 200 and records the payment once. */
    @Test
    void verifyIsIdempotentOnReplay() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        String token = readAccessToken(reg);
        long accountId = accountIdOf(reg);
        String orderId = "order_REPLAY_1";
        String paymentId = "pay_REPLAY_1";
        seedPending(orderId, accountId, "pro-monthly", 120000);
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);
        String bodyJson = verifyBody(orderId, paymentId, signature);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/billing/verify")
                            .header("X-Forwarded-For", uniqueIp())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(bodyJson))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/billing/invoices")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)); // recorded once, not twice
    }

    @Test
    void verifyWithBadSignatureIsRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody("order_X", "pay_X", "deadbeef")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhookWithValidSignatureActivatesAccount() throws Exception {
        MvcResult reg = register(uniqueEmail(), PASSWORD);
        String token = readAccessToken(reg);
        Map<?, ?> body = json.readValue(reg.getResponse().getContentAsString(), Map.class);
        Object accountId = ((Map<?, ?>) body.get("account")).get("id");

        String rawBody = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_HOOK1\",\"amount\":99900,\"currency\":\"INR\",\"notes\":"
                + "{\"accountId\":\"" + accountId + "\"}}}}}";
        String sig = RazorpaySignature.hmacHex(WEBHOOK_SECRET, rawBody);

        mvc.perform(post("/api/billing/webhook")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("X-Razorpay-Signature", sig)
                        .contentType(APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isOk());

        mvc.perform(get("/api/billing/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void webhookWithBadSignatureIsUnauthorized() throws Exception {
        String rawBody = "{\"event\":\"payment.captured\",\"payload\":{}}";
        mvc.perform(post("/api/billing/webhook")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("X-Razorpay-Signature", "not-a-valid-signature")
                        .contentType(APPLICATION_JSON)
                        .content(rawBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void devActivateWorksInDevMode() throws Exception {
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        mvc.perform(post("/api/billing/dev-activate")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.currentPeriodEnd").isNotEmpty());
    }

    private String verifyBody(String orderId, String paymentId, String signature) throws Exception {
        return verifyBody(orderId, paymentId, signature, null);
    }

    private String verifyBody(String orderId, String paymentId, String signature, String plan) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("razorpay_order_id", orderId);
        m.put("razorpay_payment_id", paymentId);
        m.put("razorpay_signature", signature);
        if (plan != null) {
            m.put("plan", plan);
        }
        return writeJson(m);
    }

    private long accountIdOf(MvcResult reg) throws Exception {
        Map<?, ?> body = json.readValue(reg.getResponse().getContentAsString(), Map.class);
        Object id = ((Map<?, ?>) body.get("account")).get("id");
        return ((Number) id).longValue();
    }

    /** Seed the server-authoritative checkout binding (checkout is 503 without live keys). */
    private void seedPending(String orderId, long accountId, String planCode, long amount) {
        pendingRepo.save(new PendingCheckout(orderId, accountId, planCode, amount, "INR", Instant.now()));
    }
}

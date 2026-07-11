package com.mayoclone.web;

import com.mayoclone.billing.RazorpaySignature;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

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
                .andExpect(jsonPath("$.plan.amount").value(99900))
                .andExpect(jsonPath("$.razorpayEnabled").value(false))
                .andExpect(jsonPath("$.devMode").value(true));
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
        String token = registerAndToken(uniqueEmail(), PASSWORD);
        String orderId = "order_TEST123";
        String paymentId = "pay_TEST123";
        String signature = RazorpaySignature.hmacHex(KEY_SECRET, orderId + "|" + paymentId);

        mvc.perform(post("/api/billing/verify")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(verifyBody(orderId, paymentId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.currentPeriodEnd").isNotEmpty());
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
        Map<String, String> m = new LinkedHashMap<>();
        m.put("razorpay_order_id", orderId);
        m.put("razorpay_payment_id", paymentId);
        m.put("razorpay_signature", signature);
        return writeJson(m);
    }
}

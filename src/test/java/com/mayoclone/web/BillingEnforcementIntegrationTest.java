package com.mayoclone.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

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
 * The 402 gate. Runs in its OWN context (separate from {@link com.mayoclone.support.AbstractIntegrationTest})
 * with {@code enforce=true} and {@code dev-mode=false} so we can assert both the
 * enforcement behaviour and that dev-activate is hidden when dev-mode is off.
 */
@SpringBootTest(properties = {
        "mayoclone.billing.enforce=true",
        "mayoclone.billing.dev-mode=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BillingEnforcementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AccountRepository accountRepo;

    @Test
    void expiredAccountGets402OnProtectedRouteButCanStillSeeStatus() throws Exception {
        String email = uniqueEmail();
        String token = registerAndToken(email);

        // Force the trial into the past and leave it un-paid → not active.
        Account a = accountRepo.findByEmailIgnoreCase(email).orElseThrow();
        a.setSubscriptionStatus(SubscriptionStatus.TRIALING);
        a.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        a.setCurrentPeriodEnd(null);
        accountRepo.save(a);

        // Protected route → blocked with 402.
        mvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("subscription_required"));

        // Billing status stays reachable so the user can pay.
        mvc.perform(get("/api/billing/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void activeTrialAccountPassesTheGate() throws Exception {
        String token = registerAndToken(uniqueEmail());
        mvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void devActivateHiddenWhenDevModeOff() throws Exception {
        String token = registerAndToken(uniqueEmail());
        mvc.perform(post("/api/billing/dev-activate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private String uniqueEmail() {
        return "enf-" + System.nanoTime() + "@test.local";
    }

    private String registerAndToken(String email) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("businessName", "Biz " + email);
        body.put("email", email);
        body.put("password", PASSWORD);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        Map<?, ?> map = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return (String) map.get("accessToken");
    }
}

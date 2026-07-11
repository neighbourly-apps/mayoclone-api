package com.mayoclone.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionPayment;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.SubscriptionPaymentRepository;
import com.mayoclone.repository.VendorRepository;
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
 * Platform super-admin API. Runs with {@code enforce=true} (its own context) so it
 * can assert both the cross-tenant admin reads/mutations AND that suspend/activate
 * flip the tenant's 402 gate — and that the SUPER_ADMIN itself is never gated.
 */
@SpringBootTest(properties = {
        "mayoclone.billing.enforce=true",
        "mayoclone.billing.dev-mode=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminApiIntegrationTest {

    private static final String SUPERADMIN_EMAIL = "superadmin@mayoclone.local";
    private static final String SUPERADMIN_PASSWORD = "superadmin123";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AccountRepository accountRepo;
    @Autowired
    private VendorRepository vendorRepo;
    @Autowired
    private SubscriptionPaymentRepository paymentRepo;

    @Test
    void superAdminCanListAccountsAcrossTenantsAndSeeStats() throws Exception {
        registerAndToken(uniqueEmail());
        registerAndToken(uniqueEmail());
        String admin = superAdminToken();

        mvc.perform(get("/api/admin/accounts").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());

        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAccounts").isNumber())
                .andExpect(jsonPath("$.trialing").isNumber())
                .andExpect(jsonPath("$.active").isNumber())
                .andExpect(jsonPath("$.mrrEstimatePaise").isNumber());
    }

    @Test
    void normalOwnerIsForbiddenFromAdminRoutes() throws Exception {
        String owner = registerAndToken(uniqueEmail());
        mvc.perform(get("/api/admin/accounts").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendBlocksTenantWith402AndActivateRestores() throws Exception {
        String email = uniqueEmail();
        String tenant = registerAndToken(email);
        long id = accountRepo.findByEmailIgnoreCase(email).orElseThrow().getId();
        String admin = superAdminToken();

        // Fresh trial → tenant can hit protected routes.
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tenant))
                .andExpect(status().isOk());

        // Suspend → CANCELLED → 402 on protected route.
        mvc.perform(post("/api/admin/accounts/" + id + "/suspend")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tenant))
                .andExpect(status().isPaymentRequired());

        // Activate 30 days → ACTIVE → 200 again.
        mvc.perform(post("/api/admin/accounts/" + id + "/activate")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(APPLICATION_JSON)
                        .content("{\"days\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tenant))
                .andExpect(status().isOk());
    }

    @Test
    void extendTrialSetsTrialingAndFutureTrialEnd() throws Exception {
        String email = uniqueEmail();
        registerAndToken(email);
        long id = accountRepo.findByEmailIgnoreCase(email).orElseThrow().getId();
        String admin = superAdminToken();

        mvc.perform(post("/api/admin/accounts/" + id + "/extend-trial")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(APPLICATION_JSON)
                        .content("{\"days\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("TRIALING"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void superAdminNotGatedEvenWhenOwnSubscriptionExpired() throws Exception {
        // Force the seeded super-admin's own subscription into the past.
        Account sa = accountRepo.findByEmailIgnoreCase(SUPERADMIN_EMAIL).orElseThrow();
        sa.setSubscriptionStatus(SubscriptionStatus.EXPIRED);
        sa.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.DAYS));
        sa.setTrialEndsAt(null);
        accountRepo.save(sa);
        String admin = superAdminToken();

        // Admin route still works (route exemption)...
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        // ...and even a tenant-shaped protected route is not 402'd (role exemption).
        mvc.perform(get("/api/orders")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void adminDtoIncludesVendorAndPaymentRollups() throws Exception {
        String email = uniqueEmail();
        registerAndToken(email);
        long id = accountRepo.findByEmailIgnoreCase(email).orElseThrow().getId();

        Vendor v = new Vendor();
        v.setAccountId(id);
        v.setRestaurantName("Test Vendor");
        v.setCreatedAt(Instant.now());
        vendorRepo.save(v);

        paymentRepo.save(new SubscriptionPayment(id, "razorpay", "pay_admin_test_1",
                99900L, "INR", Instant.now()));

        String admin = superAdminToken();

        // Detail view.
        mvc.perform(get("/api/admin/accounts/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.vendorCount").value(1))
                .andExpect(jsonPath("$.account.orderCount").value(0))
                .andExpect(jsonPath("$.account.totalPaidPaise").value(99900))
                .andExpect(jsonPath("$.account.lastPaymentAt").isNotEmpty())
                .andExpect(jsonPath("$.payments[0].providerPaymentId").value("pay_admin_test_1"))
                .andExpect(jsonPath("$.mailboxes[0].sourceType").value("IMAP"));

        // List view (filtered by email).
        mvc.perform(get("/api/admin/accounts").param("q", email)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value(email))
                .andExpect(jsonPath("$.content[0].vendorCount").value(1))
                .andExpect(jsonPath("$.content[0].totalPaidPaise").value(99900));
    }

    // ---- helpers ----

    private static int seq = 0;

    private String uniqueEmail() {
        return "admin-it-" + (seq++) + "-" + System.nanoTime() + "@test.local";
    }

    /** Unique per call so the per-IP auth rate buckets (10/min) never collide across tests. */
    private String uniqueIp() {
        int n = ++seq;
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    private String registerAndToken(String email) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("businessName", "Biz " + email);
        body.put("email", email);
        body.put("password", PASSWORD);
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return readToken(res);
    }

    private String superAdminToken() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", SUPERADMIN_EMAIL);
        body.put("password", SUPERADMIN_PASSWORD);
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        return readToken(res);
    }

    private String readToken(MvcResult res) throws Exception {
        Map<?, ?> map = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return (String) map.get("accessToken");
    }
}

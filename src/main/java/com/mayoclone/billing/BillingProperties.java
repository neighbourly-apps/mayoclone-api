package com.mayoclone.billing;

import com.mayoclone.billing.BillingDtos.PlanDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central, feature-off-safe billing configuration. Every value has a safe default
 * so the app boots and builds with NO Razorpay keys and NO Docker.
 *
 * <ul>
 *   <li>{@link #isEnforce()} — when true, expired accounts are blocked with 402.</li>
 *   <li>{@link #isDevMode()} — when true, exposes {@code /api/billing/dev-activate}
 *       to simulate a successful payment without Razorpay.</li>
 *   <li>{@link #isRazorpayEnabled()} — true only when BOTH a key-id and key-secret
 *       are configured; gates order creation in {@code /api/billing/checkout}.</li>
 * </ul>
 *
 * <p>There are TWO plans (both env-overridable): {@code pro-monthly} (₹1200 / 30d)
 * and {@code pro-annual} (₹12999 / 365d). {@link #plans()} lists both (monthly first);
 * {@link #plan(String)} resolves a code (400 for an unknown one); {@link #defaultPlan()}
 * is the checkout default. All money is in the currency's minor unit (paise for INR).
 *
 * <p>Signature verification for {@code /verify} needs only the key-secret; webhook
 * verification needs only the webhook-secret — so those can be exercised in tests
 * even while {@code checkout} stays "not configured" (503).
 */
@Component
public class BillingProperties {

    /** Fixed plan codes (the map keys); name/amount/currency/period-days are env-overridable. */
    public static final String CODE_MONTHLY = "pro-monthly";
    public static final String CODE_ANNUAL = "pro-annual";

    private final boolean enforce;
    private final boolean devMode;

    /** Keyed by plan code. */
    private final Map<String, PlanDto> plans;
    /** Monthly-then-annual, for the ordered status list. */
    private final java.util.List<PlanDto> orderedPlans;
    private final String defaultPlanCode;

    private final String razorpayKeyId;
    private final String razorpayKeySecret;
    private final String razorpayWebhookSecret;

    public BillingProperties(
            @Value("${mayoclone.billing.enforce:true}") boolean enforce,
            @Value("${mayoclone.billing.dev-mode:false}") boolean devMode,
            @Value("${mayoclone.billing.plans.pro-monthly.name:Monthly}") String monthlyName,
            @Value("${mayoclone.billing.plans.pro-monthly.amount:120000}") long monthlyAmount,
            @Value("${mayoclone.billing.plans.pro-monthly.currency:INR}") String monthlyCurrency,
            @Value("${mayoclone.billing.plans.pro-monthly.period-days:30}") int monthlyPeriodDays,
            @Value("${mayoclone.billing.plans.pro-annual.name:Annual}") String annualName,
            @Value("${mayoclone.billing.plans.pro-annual.amount:1299900}") long annualAmount,
            @Value("${mayoclone.billing.plans.pro-annual.currency:INR}") String annualCurrency,
            @Value("${mayoclone.billing.plans.pro-annual.period-days:365}") int annualPeriodDays,
            @Value("${mayoclone.billing.default-plan-code:pro-monthly}") String defaultPlanCode,
            @Value("${mayoclone.razorpay.key-id:}") String razorpayKeyId,
            @Value("${mayoclone.razorpay.key-secret:}") String razorpayKeySecret,
            @Value("${mayoclone.razorpay.webhook-secret:}") String razorpayWebhookSecret) {
        this.enforce = enforce;
        this.devMode = devMode;

        Map<String, PlanDto> map = new LinkedHashMap<>();
        map.put(CODE_MONTHLY, new PlanDto(CODE_MONTHLY, monthlyName, monthlyAmount, monthlyCurrency, monthlyPeriodDays));
        map.put(CODE_ANNUAL, new PlanDto(CODE_ANNUAL, annualName, annualAmount, annualCurrency, annualPeriodDays));
        this.plans = Map.copyOf(map);
        // Preserve monthly-then-annual order for the list view independently of Map.copyOf.
        this.orderedPlans = java.util.List.of(map.get(CODE_MONTHLY), map.get(CODE_ANNUAL));

        String dpc = defaultPlanCode == null ? "" : defaultPlanCode.trim();
        this.defaultPlanCode = this.plans.containsKey(dpc) ? dpc : CODE_MONTHLY;

        this.razorpayKeyId = trim(razorpayKeyId);
        this.razorpayKeySecret = trim(razorpayKeySecret);
        this.razorpayWebhookSecret = trim(razorpayWebhookSecret);
    }

    public boolean isEnforce() {
        return enforce;
    }

    public boolean isDevMode() {
        return devMode;
    }

    /** Both plans, monthly first, for the status payload. */
    public Collection<PlanDto> plans() {
        return orderedPlans;
    }

    public String getDefaultPlanCode() {
        return defaultPlanCode;
    }

    public PlanDto defaultPlan() {
        return plans.get(defaultPlanCode);
    }

    /**
     * Resolve a plan by code. A null/blank code falls back to {@link #defaultPlan()};
     * an unknown code is a client error (400).
     */
    public PlanDto plan(String code) {
        if (code == null || code.isBlank()) {
            return defaultPlan();
        }
        PlanDto p = plans.get(code.trim());
        if (p == null) {
            throw new BillingException(HttpStatus.BAD_REQUEST, "unknown_plan");
        }
        return p;
    }

    /** Resolve a stored account plan code for reporting; null/unknown → default plan. */
    public PlanDto planOrDefault(String code) {
        if (code == null || code.isBlank()) {
            return defaultPlan();
        }
        return plans.getOrDefault(code.trim(), defaultPlan());
    }

    public String getRazorpayKeyId() {
        return razorpayKeyId;
    }

    public String getRazorpayKeySecret() {
        return razorpayKeySecret;
    }

    public String getRazorpayWebhookSecret() {
        return razorpayWebhookSecret;
    }

    /** Order creation (checkout) is available only when key-id AND key-secret are set. */
    public boolean isRazorpayEnabled() {
        return !razorpayKeyId.isBlank() && !razorpayKeySecret.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}

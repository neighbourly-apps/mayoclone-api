package com.mayoclone.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * <p>Signature verification for {@code /verify} needs only the key-secret; webhook
 * verification needs only the webhook-secret — so those can be exercised in tests
 * even while {@code checkout} stays "not configured" (503).
 */
@Component
public class BillingProperties {

    private final boolean enforce;
    private final boolean devMode;

    private final String planCode;
    private final String planName;
    private final long planAmount;
    private final String planCurrency;
    private final int planPeriodDays;

    private final String razorpayKeyId;
    private final String razorpayKeySecret;
    private final String razorpayWebhookSecret;

    public BillingProperties(
            @Value("${mayoclone.billing.enforce:true}") boolean enforce,
            @Value("${mayoclone.billing.dev-mode:false}") boolean devMode,
            @Value("${mayoclone.billing.plan.code:pro-monthly}") String planCode,
            @Value("${mayoclone.billing.plan.name:MayoClone Pro}") String planName,
            @Value("${mayoclone.billing.plan.amount:99900}") long planAmount,
            @Value("${mayoclone.billing.plan.currency:INR}") String planCurrency,
            @Value("${mayoclone.billing.plan.period-days:30}") int planPeriodDays,
            @Value("${mayoclone.razorpay.key-id:}") String razorpayKeyId,
            @Value("${mayoclone.razorpay.key-secret:}") String razorpayKeySecret,
            @Value("${mayoclone.razorpay.webhook-secret:}") String razorpayWebhookSecret) {
        this.enforce = enforce;
        this.devMode = devMode;
        this.planCode = planCode;
        this.planName = planName;
        this.planAmount = planAmount;
        this.planCurrency = planCurrency;
        this.planPeriodDays = planPeriodDays;
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

    public String getPlanCode() {
        return planCode;
    }

    public String getPlanName() {
        return planName;
    }

    public long getPlanAmount() {
        return planAmount;
    }

    public String getPlanCurrency() {
        return planCurrency;
    }

    public int getPlanPeriodDays() {
        return planPeriodDays;
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

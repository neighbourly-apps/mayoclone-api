package com.mayoclone.billing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Request/response payloads for the billing endpoints. */
public final class BillingDtos {

    private BillingDtos() {
    }

    /** A single plan (code + display name + amount in minor units + currency + period). */
    public record PlanDto(String code, String name, long amount, String currency, int periodDays) {
    }

    /**
     * GET /api/billing/status response.
     *
     * <p>{@code plan} is the account's CURRENT plan (resolved from its stored plan
     * code, or the default plan when none is set) — so {@code plan.code} is the
     * current plan code. {@code plans} lists every purchasable plan (monthly first).
     */
    public record BillingStatus(
            String status,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            boolean active,
            int trialDaysLeft,
            PlanDto plan,
            List<PlanDto> plans,
            boolean razorpayEnabled,
            boolean devMode) {
    }

    /** POST /api/billing/checkout request — optional plan code (defaults to pro-monthly). */
    public record CheckoutRequest(String plan) {
    }

    /** POST /api/billing/checkout response (when Razorpay is configured). Echoes the plan code. */
    public record CheckoutResponse(String keyId, String orderId, long amount, String currency,
                                   String name, String plan) {
    }

    /**
     * POST /api/billing/verify request — mirrors Razorpay's checkout callback fields,
     * plus the {@code plan} code the client checked out with (optional; default pro-monthly).
     */
    public record VerifyRequest(
            @JsonProperty("razorpay_order_id") String razorpayOrderId,
            @JsonProperty("razorpay_payment_id") String razorpayPaymentId,
            @JsonProperty("razorpay_signature") String razorpaySignature,
            String plan) {
    }

    /** POST /api/billing/dev-activate request — optional plan code (defaults to pro-monthly). */
    public record DevActivateRequest(String plan) {
    }
}

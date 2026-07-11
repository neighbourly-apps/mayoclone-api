package com.mayoclone.billing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Request/response payloads for the billing endpoints. */
public final class BillingDtos {

    private BillingDtos() {
    }

    /** The configured plan, echoed to the client. */
    public record PlanDto(String code, String name, long amount, String currency, int periodDays) {
    }

    /** GET /api/billing/status response. */
    public record BillingStatus(
            String status,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            boolean active,
            int trialDaysLeft,
            PlanDto plan,
            boolean razorpayEnabled,
            boolean devMode) {
    }

    /** POST /api/billing/checkout response (when Razorpay is configured). */
    public record CheckoutResponse(String keyId, String orderId, long amount, String currency, String name) {
    }

    /** POST /api/billing/verify request — mirrors Razorpay's checkout callback fields. */
    public record VerifyRequest(
            @JsonProperty("razorpay_order_id") String razorpayOrderId,
            @JsonProperty("razorpay_payment_id") String razorpayPaymentId,
            @JsonProperty("razorpay_signature") String razorpaySignature) {
    }
}

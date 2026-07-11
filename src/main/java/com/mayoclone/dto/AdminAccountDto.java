package com.mayoclone.dto;

import java.time.Instant;

/**
 * Cross-tenant view of an account for the platform super-admin panel. Combines the
 * account's billing state with rolled-up usage counters. NEVER exposes the password
 * hash or any mailbox secret.
 *
 * @param active   {@code account.isSubscriptionActive(now)} — the effective access
 *                 flag (a live paid period OR a live trial), computed server-side.
 */
public record AdminAccountDto(
        Long id,
        String email,
        String businessName,
        String role,
        String subscriptionStatus,
        boolean active,
        Instant trialEndsAt,
        Instant currentPeriodEnd,
        String plan,
        Instant createdAt,
        long orderCount,
        long vendorCount,
        Instant lastPaymentAt,
        long totalPaidPaise
) {
}

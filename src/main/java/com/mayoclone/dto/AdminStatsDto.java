package com.mayoclone.dto;

/**
 * Platform-wide KPIs for the super-admin dashboard. Tenant counters exclude the
 * SUPER_ADMIN account(s) so the platform operator never inflates the metrics.
 *
 * @param totalAccounts    tenant accounts (excludes SUPER_ADMIN)
 * @param trialing         tenant accounts in TRIALING
 * @param active           tenant accounts in ACTIVE
 * @param expired          tenant accounts in EXPIRED
 * @param cancelled        tenant accounts in CANCELLED
 * @param newLast7Days     tenant accounts created within the last 7 days
 * @param totalRevenuePaise sum of ALL captured subscription payments (paise)
 * @param payingAccounts   distinct accounts that have ever paid
 * @param mrrEstimatePaise  MRR estimate = {@code active} × plan monthly amount (paise)
 */
public record AdminStatsDto(
        long totalAccounts,
        long trialing,
        long active,
        long expired,
        long cancelled,
        long newLast7Days,
        long totalRevenuePaise,
        long payingAccounts,
        long mrrEstimatePaise
) {
}

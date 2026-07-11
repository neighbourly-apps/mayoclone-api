package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An {@code Account} is a restaurant business: it is BOTH the tenant boundary
 * (all vendors/orders are isolated per account) AND the login identity
 * (email + password). Business-profile fields used on invoices (station, gstin,
 * address) live here; mailbox-specific fields live on {@link Vendor}.
 *
 * <p>{@code passwordHash} is an Argon2 hash and is NEVER exposed in any DTO or
 * {@code toString()}.
 */
@Entity
@Table(name = "account")
public class Account {

    /** Role values, stored as strings. */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_ADMIN = "ADMIN";
    /**
     * Platform operator. Crosses tenant isolation: sees EVERY account and is never
     * gated by the per-tenant subscription paywall. JWT authority is
     * {@code ROLE_SUPER_ADMIN}.
     */
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String businessName;

    /** Login identity. Unique, lower-cased on write. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Argon2 password hash. Never exposed. */
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = true)
    private String stationCode;

    @Column(nullable = true)
    private String stationName;

    @Column(nullable = true)
    private String gstin;

    @Column(nullable = true)
    private String addressLine;

    @Column(nullable = true)
    private String phone;

    /** {@link #ROLE_OWNER} (default) or {@link #ROLE_ADMIN}. */
    @Column(nullable = false)
    private String role = ROLE_OWNER;

    @Column(nullable = false)
    private String status = STATUS_ACTIVE;

    /** True once the owner has proven control of their email via an OTP. */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(nullable = false)
    private Instant createdAt;

    // --- Billing / subscription (V14) ------------------------------------------

    /** Trial/paid lifecycle, stored as a string. Defaults to TRIALING on a new row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 16)
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.TRIALING;

    /** When the 14-day free trial lapses (set on register). */
    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    /** End of the current PAID period; extended on each successful payment. */
    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    /** Plan code the account is subscribed to (e.g. {@code pro-monthly}). */
    @Column(name = "plan", length = 64)
    private String plan;

    public Account() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public SubscriptionStatus getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(SubscriptionStatus subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(Instant trialEndsAt) {
        this.trialEndsAt = trialEndsAt;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(Instant currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    /**
     * Whether the account currently has access: either a live PAID period
     * (ACTIVE with a future {@code currentPeriodEnd}) OR a live free trial
     * (TRIALING with a future {@code trialEndsAt}). Computed on the fly, so an
     * un-swept expired trial still evaluates as inactive.
     */
    public boolean isSubscriptionActive(Instant now) {
        if (subscriptionStatus == SubscriptionStatus.ACTIVE
                && currentPeriodEnd != null && currentPeriodEnd.isAfter(now)) {
            return true;
        }
        return subscriptionStatus == SubscriptionStatus.TRIALING
                && trialEndsAt != null && trialEndsAt.isAfter(now);
    }

    /** NEVER leak the password hash. */
    @Override
    public String toString() {
        return "Account{id=" + id + ", email='" + email + "', role='" + role + "'}";
    }
}

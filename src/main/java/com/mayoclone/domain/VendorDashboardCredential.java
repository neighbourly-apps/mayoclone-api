package com.mayoclone.domain;

import com.mayoclone.security.crypto.AesGcmStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-vendor login for the aggregator's vendor DASHBOARD (e.g. the IRCTC eCatering
 * partner portal). Used ONLY by the dashboard order-enrichment framework to look an
 * order up by its {@code externalOrderId} and back-fill fields the confirmation
 * email omitted (chiefly the passenger name).
 *
 * <p>One row per vendor ({@code vendor_id} is unique). The {@code username},
 * {@code password} and captured {@code sessionToken} are encrypted at rest with
 * AES-256-GCM via {@link AesGcmStringConverter} — EXACTLY like {@code Vendor.imapPassword}
 * — and are NEVER exposed in any DTO or {@code toString()}.
 */
@Entity
@Table(name = "vendor_dashboard_credential", indexes = {
        @Index(name = "idx_dash_cred_account", columnList = "account_id"),
        @Index(name = "uk_dash_cred_vendor", columnList = "vendor_id", unique = true)
})
public class VendorDashboardCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant/business. Never set from a request body — derived from the caller. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** The vendor this login belongs to. Unique — one dashboard login per vendor. */
    @Column(name = "vendor_id", nullable = false, unique = true)
    private Long vendorId;

    /** Which dashboard provider these credentials are for. Selects the {@code DashboardScraper}. */
    @Column(name = "provider", nullable = false, length = 64)
    private String provider = "IRCTC_ECATERING";

    /** Dashboard login username. Encrypted at rest (AES-256-GCM). Never returned in a DTO. */
    @Convert(converter = AesGcmStringConverter.class)
    @Column(name = "username", length = 1024)
    private String username;

    /** Dashboard login password. Encrypted at rest (AES-256-GCM). Never returned in a DTO. */
    @Convert(converter = AesGcmStringConverter.class)
    @Column(name = "password", length = 1024)
    private String password;

    /**
     * Opaque session captured after a successful login (a bearer token or serialized
     * cookie jar) so subsequent order lookups skip re-login until it expires. Encrypted
     * at rest; nullable until the first login. Never returned in a DTO.
     */
    @Convert(converter = AesGcmStringConverter.class)
    @Column(name = "session_token", length = 4096)
    private String sessionToken;

    /** When {@link #sessionToken} lapses; null when there is no cached session. */
    @Column(name = "session_expires_at")
    private Instant sessionExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DashboardCredentialStatus status = DashboardCredentialStatus.NEW;

    /** Truncated last enrichment error (surfaced as a badge); cleared on success. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public VendorDashboardCredential() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getVendorId() {
        return vendorId;
    }

    public void setVendorId(Long vendorId) {
        this.vendorId = vendorId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public Instant getSessionExpiresAt() {
        return sessionExpiresAt;
    }

    public void setSessionExpiresAt(Instant sessionExpiresAt) {
        this.sessionExpiresAt = sessionExpiresAt;
    }

    public DashboardCredentialStatus getStatus() {
        return status;
    }

    public void setStatus(DashboardCredentialStatus status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Never leaks secrets. */
    @Override
    public String toString() {
        return "VendorDashboardCredential{id=" + id + ", vendorId=" + vendorId
                + ", provider=" + provider + ", status=" + status + "}";
    }
}

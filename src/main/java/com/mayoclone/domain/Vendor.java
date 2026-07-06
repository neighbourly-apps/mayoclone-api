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
 * A mailbox/source that belongs to an {@link Account} (the business). At signup
 * the vendor shares their mailbox, which we then scrape over IMAP for the
 * configured aggregators' order emails.
 *
 * <p>The IMAP password is encrypted at rest with AES-256-GCM via
 * {@link AesGcmStringConverter} and is NEVER exposed in any DTO or toString().
 */
@Entity
@Table(name = "vendor", indexes = @Index(name = "idx_vendor_account", columnList = "account_id"))
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant/business. Never set from a request body — derived from the caller. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    private String restaurantName;

    /** The mailbox the vendor shares at signup. */
    private String ownerEmail;

    /**
     * How this vendor's mail reaches us. Defaults to {@link MailSourceType#IMAP}
     * for backward compatibility with pre-existing rows.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private MailSourceType sourceType = MailSourceType.IMAP;

    /**
     * FORWARDING only: the per-vendor address the vendor forwards aggregator mail
     * to, e.g. {@code <token>@<MAYOCLONE_INBOUND_DOMAIN>}. Unique. Minted server-side.
     */
    @Column(name = "ingest_address", unique = true, nullable = true)
    private String ingestAddress;

    /** GMAIL_OAUTH only: the connected Google account email. */
    @Column(name = "oauth_email", nullable = true)
    private String oauthEmail;

    /** GMAIL_OAUTH only: encrypted at rest (AES-256-GCM). Never returned in a DTO. */
    @Convert(converter = AesGcmStringConverter.class)
    @Column(name = "oauth_refresh_token", length = 2048, nullable = true)
    private String oauthRefreshToken;

    /** GMAIL_OAUTH only: when the OAuth consent was granted. */
    @Column(name = "oauth_connected_at", nullable = true)
    private Instant oauthConnectedAt;

    /** Station the vendor serves, e.g. "NDLS". */
    private String stationCode;

    @Column(nullable = true)
    private String stationName;

    private String phone;

    /** GST identification number, for invoices. */
    @Column(nullable = true)
    private String gstin;

    @Column(nullable = true)
    private String addressLine;

    private String imapHost;
    private int imapPort = 993;

    /** Defaults to {@link #ownerEmail} if left blank at signup. */
    private String imapUsername;

    /** Encrypted at rest (AES-256-GCM). Never returned in a DTO. */
    @Convert(converter = AesGcmStringConverter.class)
    @Column(length = 1024)
    private String imapPassword;

    private boolean useSsl = true;
    private boolean active = true;

    /** Last successful sync time; null until first sync. */
    @Column(nullable = true)
    private Instant lastSyncedAt;

    private Instant createdAt;

    public Vendor() {
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

    /** Never null in practice; treats a legacy null as {@link MailSourceType#IMAP}. */
    public MailSourceType getSourceType() {
        return sourceType == null ? MailSourceType.IMAP : sourceType;
    }

    public void setSourceType(MailSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getIngestAddress() {
        return ingestAddress;
    }

    public void setIngestAddress(String ingestAddress) {
        this.ingestAddress = ingestAddress;
    }

    public String getOauthEmail() {
        return oauthEmail;
    }

    public void setOauthEmail(String oauthEmail) {
        this.oauthEmail = oauthEmail;
    }

    public String getOauthRefreshToken() {
        return oauthRefreshToken;
    }

    public void setOauthRefreshToken(String oauthRefreshToken) {
        this.oauthRefreshToken = oauthRefreshToken;
    }

    public Instant getOauthConnectedAt() {
        return oauthConnectedAt;
    }

    public void setOauthConnectedAt(Instant oauthConnectedAt) {
        this.oauthConnectedAt = oauthConnectedAt;
    }

    public String getRestaurantName() {
        return restaurantName;
    }

    public void setRestaurantName(String restaurantName) {
        this.restaurantName = restaurantName;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public void setImapPort(int imapPort) {
        this.imapPort = imapPort;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public void setImapUsername(String imapUsername) {
        this.imapUsername = imapUsername;
    }

    public String getImapPassword() {
        return imapPassword;
    }

    public void setImapPassword(String imapPassword) {
        this.imapPassword = imapPassword;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

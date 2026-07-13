package com.mayoclone.domain;

import jakarta.persistence.Column;
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
 * Dead-letter record for a raw message we could not turn into an order (bad
 * route, no parser, parse error, or an unmatched inbound recipient). Kept so an
 * operator can inspect and retry it. {@link #rawSnippet} is truncated and MUST
 * NOT contain secrets/tokens/passwords.
 */
@Entity
@Table(name = "ingest_failure", indexes = {
        @Index(name = "idx_ingest_failure_account", columnList = "account_id, created_at")
})
public class IngestFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant; nullable when an inbound recipient matched no vendor. */
    @Column(name = "account_id", nullable = true)
    private Long accountId;

    @Column(name = "vendor_id", nullable = true)
    private Long vendorId;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(length = 1000)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngestFailureReason reason;

    /** Truncated body snippet for triage/list views — NEVER secrets. */
    @Column(name = "raw_snippet", length = 2000)
    private String rawSnippet;

    /**
     * The FULL, untruncated body — the replay source. When a new aggregator/parser
     * is added, the stored email can be re-ingested faithfully (a truncated snippet
     * could not be re-parsed).
     */
    @Column(name = "raw_body", columnDefinition = "text")
    private String rawBody;

    /** Set when a retry re-ingested this message successfully (audit trail); null while unresolved. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32)
    private MailSourceType sourceType;

    @Column(name = "message_id", length = 512)
    private String messageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public IngestFailure() {
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

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public IngestFailureReason getReason() {
        return reason;
    }

    public void setReason(IngestFailureReason reason) {
        this.reason = reason;
    }

    public String getRawSnippet() {
        return rawSnippet;
    }

    public void setRawSnippet(String rawSnippet) {
        this.rawSnippet = rawSnippet;
    }

    public String getRawBody() {
        return rawBody;
    }

    public void setRawBody(String rawBody) {
        this.rawBody = rawBody;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public MailSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(MailSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

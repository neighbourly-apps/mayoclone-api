package com.mayoclone.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An IRCTC e-catering aggregator (Zoop, RailRestro, Comesum, …) whose order
 * confirmation emails we scrape from a vendor's mailbox.
 *
 * <p>This is a first-class, managed DB table (seeded on startup and editable via
 * the REST API) rather than a hardcoded enum, so partners can be added/tuned at
 * runtime. Incoming mail is routed to an aggregator by matching the sender
 * address against {@link #senderDomains}.
 */
@Entity
@Table(name = "aggregator")
public class Aggregator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable machine code, e.g. "ZOOP". Unique. */
    @Column(unique = true, nullable = false)
    private String code;

    /** Human-friendly name, e.g. "Zoop". */
    private String name;

    /**
     * Sender domains used to route scraped mail to this aggregator, e.g.
     * ["zoopindia.com"]. Best-effort defaults are seeded on startup and are
     * fully editable via the API.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "aggregator_sender_domain",
            joinColumns = @JoinColumn(name = "aggregator_id")
    )
    @Column(name = "domain")
    private List<String> senderDomains = new ArrayList<>();

    /** Optional subject substring hint to help disambiguate. */
    @Column(nullable = true)
    private String subjectHint;

    /** Hex brand colour for the UI, e.g. "#E4002B". */
    private String brandColor;

    @Column(nullable = true)
    private String websiteUrl;

    private boolean active = true;

    /**
     * Commission this aggregator charges, as a fraction (0.15 = 15%). Drives
     * settlement/reconciliation math. Defaults to 0.15.
     */
    @Column(name = "commission_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal commissionRate = new BigDecimal("0.15");

    private Instant createdAt;

    public Aggregator() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getSenderDomains() {
        return senderDomains;
    }

    public void setSenderDomains(List<String> senderDomains) {
        this.senderDomains = senderDomains;
    }

    public String getSubjectHint() {
        return subjectHint;
    }

    public void setSubjectHint(String subjectHint) {
        this.subjectHint = subjectHint;
    }

    public String getBrandColor() {
        return brandColor;
    }

    public void setBrandColor(String brandColor) {
        this.brandColor = brandColor;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

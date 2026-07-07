package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A one-time email verification code. Only the SHA-256 {@code codeHash} of the
 * 6-digit code is persisted — never the code itself. At most one UNCONSUMED row
 * per email is active at a time: sending a new code marks any prior unconsumed
 * codes for that email as consumed. Each row tracks an {@code attempts} counter
 * (max 5) and a 10-minute {@code expiresAt}.
 */
@Entity
@Table(
        name = "email_otp",
        indexes = {
                @Index(name = "idx_email_otp_email", columnList = "email, consumed, created_at")
        }
)
public class EmailOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-cased email the code was sent to. */
    @Column(nullable = false, length = 255)
    private String email;

    /** SHA-256 hex of the 6-digit code. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /** Failed verify attempts against this code. */
    @Column(nullable = false)
    private int attempts = 0;

    /** True once verified successfully (or superseded by a newer code). */
    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public EmailOtp() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}

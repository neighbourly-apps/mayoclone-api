package com.mayoclone.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Idempotency ledger row for a Google Pub/Sub push delivery, keyed by the
 * Pub/Sub {@code message.messageId} (which is stable across at-least-once
 * re-deliveries of the same message). The Gmail push endpoint records one row per
 * message it has handled so a re-delivery can be short-circuited.
 *
 * <p>This is an OPTIMISATION only — end-to-end correctness is guaranteed by the
 * job queue's {@code dedup_key} coalescing and the ingestion pipeline's
 * Message-ID dedup — so old rows are safely purged on a retention window.
 */
@Entity
@Table(name = "processed_push")
public class ProcessedPush {

    /** The Pub/Sub {@code message.messageId}. Natural primary key (no generation). */
    @Id
    @Column(name = "message_id", length = 255, nullable = false)
    private String messageId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public ProcessedPush() {
    }

    public ProcessedPush(String messageId, Instant receivedAt) {
        this.messageId = messageId;
        this.receivedAt = receivedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}

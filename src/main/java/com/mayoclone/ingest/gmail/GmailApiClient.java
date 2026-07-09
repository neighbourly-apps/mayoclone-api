package com.mayoclone.ingest.gmail;

import java.util.List;

/**
 * Thin seam over the handful of Gmail REST calls the push-ingestion path needs.
 * Extracting an interface keeps every live Google HTTP call behind a single
 * mockable boundary so the history-sync / watch logic is unit-testable WITHOUT a
 * network. The live implementation is {@link RestGmailApiClient}; tests supply a
 * mock.
 *
 * <p>All methods take an already-refreshed OAuth access token. Transient failures
 * (5xx, network) surface as unchecked exceptions so the caller can retry via the
 * job queue; a too-old {@code startHistoryId} surfaces as {@link HistoryGoneException}.
 */
public interface GmailApiClient {

    /** One page of {@code users.history.list} results. */
    record HistoryPage(List<String> addedMessageIds, String nextPageToken, String historyId) {
    }

    /** Result of a {@code users.watch} registration. */
    record WatchResult(String historyId, long expiration) {
    }

    /** Thrown when {@code users.history.list} returns 404 (startHistoryId too old / expired). */
    class HistoryGoneException extends RuntimeException {
        public HistoryGoneException(String message) {
            super(message);
        }
    }

    /**
     * {@code users.history.list} for {@code messageAdded} events on the INBOX label,
     * starting at {@code startHistoryId}. {@code pageToken} is null for the first page.
     *
     * @throws HistoryGoneException on a 404 (the history window has expired)
     */
    HistoryPage listHistory(String accessToken, String startHistoryId, String pageToken);

    /** {@code users.messages.get?format=RAW} → decoded RFC-822 bytes, or null if unavailable. */
    byte[] getMessageRaw(String accessToken, String messageId);

    /** {@code users.messages.list?labelIds=INBOX} → message ids (fallback recovery path). */
    List<String> listInboxMessageIds(String accessToken, int maxResults);

    /** {@code users.getProfile} → the mailbox's current {@code historyId} (rebaseline). */
    String profileHistoryId(String accessToken);

    /** {@code users.watch} for INBOX against the given Pub/Sub topic. */
    WatchResult watch(String accessToken, String topicName);

    /** {@code users.stop} — deregister the current watch. Best-effort. */
    void stop(String accessToken);
}

package com.mayoclone.ingest.gmail;

/**
 * Published by {@link GmailOAuthService} after a mailbox's refresh token is stored
 * on a successful OAuth connect. {@link GmailWatchService} listens for it to start
 * a {@code users.watch} registration (best-effort, decoupled so the OAuth service
 * and the watch service do not form a dependency cycle).
 */
public record GmailConnectedEvent(long vendorId) {
}

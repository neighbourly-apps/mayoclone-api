package com.mayoclone.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for tenant-supplied outbound webhook URLs. A tenant must not be able to
 * point the notification webhook at internal infrastructure (cloud metadata at
 * 169.254.169.254, RFC1918 private ranges, loopback, link-local, unique-local IPv6).
 *
 * <p>Two gates:
 * <ul>
 *   <li>{@link #validateForSave(String)} — at save time: require {@code https} and a
 *       host, and reject a host that is a literal blocked IP or resolves to one. A host
 *       that does not resolve yet is ALLOWED at save (deferred to dispatch); the real
 *       enforcement is post-DNS at send.</li>
 *   <li>{@link #isSafeToDispatch(String)} — at dispatch: re-resolve and FAIL CLOSED
 *       (skip) if the URL is not https, has no host, fails to resolve, or ANY resolved
 *       address is blocked. Re-checking here defeats DNS rebinding done after save.</li>
 * </ul>
 */
public final class WebhookUrlGuard {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlGuard.class);

    /** Thrown by {@link #validateForSave} when a URL is syntactically/semantically unsafe. */
    public static final class InvalidWebhookUrlException extends RuntimeException {
        public InvalidWebhookUrlException(String message) {
            super(message);
        }
    }

    private WebhookUrlGuard() {
    }

    /** Save-time validation. Throws {@link InvalidWebhookUrlException} when unsafe. */
    public static void validateForSave(String url) {
        URI uri = parseHttps(url);
        if (uri == null) {
            throw new InvalidWebhookUrlException("webhook URL must be an absolute https:// URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException("webhook URL must have a host");
        }
        // Reject an obvious internal target now. A host that doesn't resolve is allowed
        // at save (can't prove it's bad); dispatch fails closed if it later resolves badly.
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return; // unresolved → allow at save; dispatch re-checks
        }
        for (InetAddress addr : addrs) {
            if (isBlocked(addr)) {
                throw new InvalidWebhookUrlException(
                        "webhook host resolves to a blocked address: " + addr.getHostAddress());
            }
        }
    }

    /** Dispatch-time check. Returns false (skip) on ANY problem — fail closed. */
    public static boolean isSafeToDispatch(String url) {
        URI uri = parseHttps(url);
        if (uri == null) {
            log.warn("Webhook dispatch blocked: not an https URL");
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlocked(addr)) {
                    log.warn("Webhook dispatch blocked: host {} resolves to blocked address {}",
                            host, addr.getHostAddress());
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            log.warn("Webhook dispatch blocked: host {} did not resolve", host);
            return false; // fail closed
        }
    }

    /** Parse and require an absolute {@code https} URL; null otherwise. */
    private static URI parseHttps(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True for loopback / link-local / private (RFC1918) / unique-local / wildcard / multicast. */
    private static boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) {
            return true; // IPv6 unique-local fc00::/7 (not covered by isSiteLocalAddress)
        }
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10 carrier-grade NAT — treat as internal.
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        }
        return false;
    }
}

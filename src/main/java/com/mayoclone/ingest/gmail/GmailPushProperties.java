package com.mayoclone.ingest.gmail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Gmail Pub/Sub push ingestion + the {@code users.watch}
 * lifecycle. EVERY value is optional and blank/default by default, so the feature
 * is OFF out of the box: with no topic set, mailboxes fall back to the existing
 * scheduled poll, and the push endpoint reports "disabled" (503).
 *
 * <ul>
 *   <li>{@code mayoclone.gmail.pubsub.topic} — the Pub/Sub topic
 *       ({@code projects/PROJECT/topics/TOPIC}) passed to {@code users.watch}. BLANK
 *       ⇒ watch disabled and Gmail vendors keep being polled.</li>
 *   <li>{@code mayoclone.gmail.push.audience} — when set, the push endpoint requires
 *       a Google-signed OIDC Bearer JWT whose {@code aud} equals this value.</li>
 *   <li>{@code mayoclone.gmail.push.token} — fallback shared-secret: when set (and no
 *       audience), the push endpoint requires a matching {@code ?token=} query param.</li>
 *   <li>{@code mayoclone.gmail.watch.renew-ms} — cadence of the watch-renewal sweep.</li>
 *   <li>{@code mayoclone.poll.skip-gmail-when-push} — when a topic is configured, the
 *       scheduled poll skips GMAIL_OAUTH vendors (push handles them). Default true.</li>
 * </ul>
 */
@Component
public class GmailPushProperties {

    private final String pubsubTopic;
    private final String pushAudience;
    private final String pushToken;
    private final long watchRenewMs;
    private final boolean skipGmailWhenPush;

    public GmailPushProperties(
            @Value("${mayoclone.gmail.pubsub.topic:}") String pubsubTopic,
            @Value("${mayoclone.gmail.push.audience:}") String pushAudience,
            @Value("${mayoclone.gmail.push.token:}") String pushToken,
            @Value("${mayoclone.gmail.watch.renew-ms:21600000}") long watchRenewMs,
            @Value("${mayoclone.poll.skip-gmail-when-push:true}") boolean skipGmailWhenPush) {
        this.pubsubTopic = pubsubTopic;
        this.pushAudience = pushAudience;
        this.pushToken = pushToken;
        this.watchRenewMs = watchRenewMs;
        this.skipGmailWhenPush = skipGmailWhenPush;
    }

    /** True when a Pub/Sub topic is configured → watch + push are active for Gmail vendors. */
    public boolean pushConfigured() {
        return notBlank(pubsubTopic);
    }

    /** True when OIDC verification is configured (an audience is set). */
    public boolean audienceConfigured() {
        return notBlank(pushAudience);
    }

    /** True when the shared-secret token fallback is configured (and no audience). */
    public boolean tokenConfigured() {
        return notBlank(pushToken);
    }

    public String pubsubTopic() {
        return pubsubTopic;
    }

    public String pushAudience() {
        return pushAudience;
    }

    public String pushToken() {
        return pushToken;
    }

    public long watchRenewMs() {
        return watchRenewMs;
    }

    public boolean skipGmailWhenPush() {
        return skipGmailWhenPush;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

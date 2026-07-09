package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.IngestJob;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.MimeEmailParser;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.jobs.JobHandler;
import com.mayoclone.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link JobHandler} that reacts to a Gmail Pub/Sub push by syncing a mailbox
 * forward from its last processed {@code historyId}. Enqueued (coalesced) by
 * {@link com.mayoclone.web.GmailPushController} and run on the durable job queue,
 * so bursts of push notifications collapse into a single sync and transient
 * failures retry with backoff.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load the vendor; skip cleanly if it is missing / inactive / not GMAIL_OAUTH
 *       / Gmail disabled (a permanent no-op — nothing to retry).</li>
 *   <li>Refresh an access token from the vendor's stored refresh token.</li>
 *   <li>Page {@code users.history.list} from {@code vendor.gmailHistoryId}, collecting
 *       added message ids, then fetch + ingest each (RAW → {@link MimeEmailParser} →
 *       {@link IngestionCore}). Advance {@code gmailHistoryId} to the newest historyId.</li>
 *   <li>On a 404 (history too old) OR when there is no baseline yet, fall back to
 *       listing recent INBOX messages, ingest them, and rebaseline {@code gmailHistoryId}
 *       from the profile.</li>
 * </ol>
 *
 * <p>Idempotent: {@link IngestionCore} dedups on Message-ID, so running twice is safe.
 * Transient errors (token/API) propagate so the queue retries; permanent conditions
 * (vendor gone) return normally.
 */
@Component
public class GmailHistoryJobHandler implements JobHandler {

    public static final String JOB_TYPE = "GMAIL_HISTORY";

    private static final Logger log = LoggerFactory.getLogger(GmailHistoryJobHandler.class);
    private static final int FALLBACK_MAX = 25;

    private final VendorRepository vendorRepo;
    private final GmailOAuthService oauth;
    private final GmailApiClient gmail;
    private final IngestionCore ingestionCore;
    private final MimeEmailParser mimeParser;

    public GmailHistoryJobHandler(VendorRepository vendorRepo,
                                  GmailOAuthService oauth,
                                  GmailApiClient gmail,
                                  IngestionCore ingestionCore,
                                  MimeEmailParser mimeParser) {
        this.vendorRepo = vendorRepo;
        this.oauth = oauth;
        this.gmail = gmail;
        this.ingestionCore = ingestionCore;
        this.mimeParser = mimeParser;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(IngestJob job) {
        Long vendorId = job.getVendorId();
        if (vendorId == null) {
            return; // malformed job — nothing to do
        }
        Vendor vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null || !vendor.isActive() || vendor.getSourceType() != MailSourceType.GMAIL_OAUTH) {
            log.debug("GMAIL_HISTORY skip: vendor {} missing/inactive/not-gmail", vendorId);
            return; // permanent no-op
        }
        if (!oauth.isEnabled() || vendor.getOauthRefreshToken() == null) {
            log.warn("GMAIL_HISTORY skip: Gmail disabled or vendor {} has no refresh token", vendorId);
            return; // feature off / not connected — nothing we can do
        }

        // Throws (BAD_GATEWAY) on a token-endpoint error → propagates → queue retries.
        String accessToken = oauth.refreshAccessToken(vendor.getOauthRefreshToken());
        if (accessToken == null) {
            throw new IllegalStateException("Gmail access-token refresh returned null for vendor " + vendorId);
        }

        String startHistoryId = vendor.getGmailHistoryId();
        if (startHistoryId == null || startHistoryId.isBlank()) {
            // No baseline yet — recover recent INBOX and set the initial historyId.
            log.info("GMAIL_HISTORY: no baseline historyId for vendor {} — recovering recent INBOX", vendorId);
            recoverRecentAndRebaseline(vendor, accessToken);
            return;
        }

        try {
            syncFromHistory(vendor, accessToken, startHistoryId);
        } catch (GmailApiClient.HistoryGoneException gone) {
            log.warn("GMAIL_HISTORY: startHistoryId {} too old for vendor {} — falling back to recent INBOX",
                    startHistoryId, vendorId);
            recoverRecentAndRebaseline(vendor, accessToken);
        }
    }

    /** Page history.list forward, ingest added messages, advance gmailHistoryId. */
    private void syncFromHistory(Vendor vendor, String accessToken, String startHistoryId) {
        Set<String> messageIds = new LinkedHashSet<>();
        String newHistoryId = startHistoryId;
        String pageToken = null;
        do {
            GmailApiClient.HistoryPage page = gmail.listHistory(accessToken, startHistoryId, pageToken);
            messageIds.addAll(page.addedMessageIds());
            if (page.historyId() != null && !page.historyId().isBlank()) {
                newHistoryId = page.historyId();
            }
            pageToken = page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

        for (String id : messageIds) {
            ingestMessage(vendor, accessToken, id);
        }
        vendor.setGmailHistoryId(newHistoryId);
        vendorRepo.save(vendor);
        log.info("GMAIL_HISTORY: vendor {} synced {} new message(s), historyId {} -> {}",
                vendor.getId(), messageIds.size(), startHistoryId, newHistoryId);
    }

    /**
     * 404 / cold-start fallback: ingest the most recent INBOX messages (idempotent),
     * then rebaseline {@code gmailHistoryId} from the profile so the NEXT push resumes
     * incrementally.
     */
    private void recoverRecentAndRebaseline(Vendor vendor, String accessToken) {
        List<String> ids = gmail.listInboxMessageIds(accessToken, FALLBACK_MAX);
        for (String id : ids) {
            ingestMessage(vendor, accessToken, id);
        }
        String profileHistoryId = gmail.profileHistoryId(accessToken);
        if (profileHistoryId != null && !profileHistoryId.isBlank()) {
            vendor.setGmailHistoryId(profileHistoryId);
        }
        vendorRepo.save(vendor);
        log.info("GMAIL_HISTORY: vendor {} recovered {} recent message(s), rebaselined historyId -> {}",
                vendor.getId(), ids.size(), vendor.getGmailHistoryId());
    }

    /** Fetch one message RAW, parse, and hand to the shared ingestion pipeline (dedups downstream). */
    private void ingestMessage(Vendor vendor, String accessToken, String messageId) {
        byte[] raw = gmail.getMessageRaw(accessToken, messageId);
        if (raw == null || raw.length == 0) {
            log.debug("GMAIL_HISTORY: message {} had no RAW payload — skipping", messageId);
            return;
        }
        RawMessage msg;
        try {
            msg = mimeParser.parse(new ByteArrayInputStream(raw));
        } catch (RuntimeException e) {
            log.warn("GMAIL_HISTORY: failed to parse message {} for vendor {}: {}",
                    messageId, vendor.getId(), e.getMessage());
            return; // one bad message must not abort the whole sync
        }
        ingestionCore.process(vendor.getAccountId(), vendor.getId(), MailSourceType.GMAIL_OAUTH, msg);
    }
}

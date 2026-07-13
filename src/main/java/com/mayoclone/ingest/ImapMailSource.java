package com.mayoclone.ingest;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link MailSource} that pulls a vendor's mailbox over IMAP, <b>read-agnostic</b>:
 * it selects messages by UID position (a stable per-message sequence), NOT by the
 * {@code \Seen} (read/unread) flag. So an email already read by another app or a
 * human is still fetched, and — because the folder is opened READ-ONLY and we never
 * set any flags — we never disturb other consumers of the same inbox. A per-mailbox
 * UID watermark ({@code vendor.imapLastUid}/{@code imapUidValidity}) makes each poll
 * incremental; Message-ID dedup downstream is the final safety net. Hardened:
 *
 * <ul>
 *   <li>TLS is enforced (protocol {@code imaps}) with server-identity verification
 *       ({@code mail.imaps.ssl.checkserveridentity=true}); plaintext IMAP is refused.</li>
 *   <li>Connection + read timeouts are set to bound a hung mailbox.</li>
 *   <li>Transient failures are retried with exponential backoff.</li>
 *   <li>First sync (or a UIDVALIDITY reset) does a bounded backfill of recent mail;
 *       each cycle processes at most {@code max-per-fetch} so a big backlog drains
 *       over several polls instead of one giant fetch.</li>
 * </ul>
 */
@Component
public class ImapMailSource implements MailSource {

    private static final Logger log = LoggerFactory.getLogger(ImapMailSource.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;

    private final int backfillCount;
    private final int maxPerFetch;
    private final int backfillSinceHours;

    public ImapMailSource(
            @Value("${mayoclone.imap.backfill-count:30}") int backfillCount,
            @Value("${mayoclone.imap.max-per-fetch:200}") int maxPerFetch,
            @Value("${mayoclone.imap.backfill-since-hours:0}") int backfillSinceHours) {
        this.backfillCount = backfillCount;
        this.maxPerFetch = maxPerFetch;
        // When > 0, the FIRST (fresh-start) sync ingests only messages sent within this
        // many hours — so we don't fetch hundreds of old bodies. 0 = disabled (use the
        // message-count backfill). The UID cursor still baselines past all recent mail,
        // so subsequent polls pick up only genuinely new orders.
        this.backfillSinceHours = backfillSinceHours;
    }

    @Override
    public MailSourceType type() {
        return MailSourceType.IMAP;
    }

    @Override
    public List<RawMessage> fetch(Vendor vendor) {
        MessagingException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetchOnce(vendor);
            } catch (MessagingException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS && isTransient(e)) {
                    long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
                    log.warn("IMAP fetch attempt {}/{} for '{}' failed transiently: {} — retrying in {}ms",
                            attempt, MAX_ATTEMPTS, vendor.getRestaurantName(), e.getMessage(), backoff);
                    sleep(backoff);
                } else {
                    break;
                }
            }
        }
        throw new RuntimeException(
                "IMAP sync failed for vendor '" + vendor.getRestaurantName() + "' ("
                        + vendor.getImapHost() + ":" + vendor.getImapPort() + "): "
                        + (last == null ? "unknown" : last.getMessage()), last);
    }

    private List<RawMessage> fetchOnce(Vendor vendor) throws MessagingException {
        Store store = null;
        Folder inbox = null;
        try {
            // Always require TLS: refuse to send credentials over a plaintext connection.
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.checkserveridentity", "true");
            props.put("mail.imaps.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
            props.put("mail.imaps.timeout", String.valueOf(READ_TIMEOUT_MS));
            props.put("mail.imaps.writetimeout", String.valueOf(READ_TIMEOUT_MS));
            // BODY.PEEK: read message bodies WITHOUT ever setting \Seen, so we never
            // disturb another app/human reading the same shared inbox — guaranteed on
            // any server, not just those that suppress \Seen in read-only mode.
            props.put("mail.imaps.peek", "true");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            String username = (vendor.getImapUsername() == null || vendor.getImapUsername().isBlank())
                    ? vendor.getOwnerEmail() : vendor.getImapUsername();
            store.connect(vendor.getImapHost(), vendor.getImapPort(), username, vendor.getImapPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY); // read-only: we never set \Seen — no interference
            return collect(vendor, inbox);
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
    }

    /**
     * Read-agnostic fetch from an already-opened, READ-ONLY INBOX. Selects messages by
     * UID (independent of read/unread), advances the vendor's UID watermark, and never
     * sets any flags. Package-private so an embedded-IMAP (GreenMail) test can drive the
     * exact UID logic without needing TLS.
     */
    List<RawMessage> collect(Vendor vendor, Folder inbox) throws MessagingException {
        UIDFolder uf = (UIDFolder) inbox;
        long validity = uf.getUIDValidity();
        Long lastUid = vendor.getImapLastUid();
        Long knownValidity = vendor.getImapUidValidity();

        Message[] candidates;
        boolean freshStart = knownValidity == null || !knownValidity.equals(validity) || lastUid == null;
        if (freshStart && backfillSinceHours > 0) {
            // Time-windowed first sync: ask the SERVER for just the recent messages
            // (IMAP SINCE, day-granular; the exact hour window is applied per-message in
            // the loop). This avoids inbox.getMessages()+per-message getUID over the WHOLE
            // mailbox, which round-trips per message and stalls on a large Gmail inbox.
            java.util.Date since = java.util.Date.from(java.time.LocalDate.now()
                    .minusDays(backfillSinceHours >= 24 ? (backfillSinceHours / 24) + 1 : 1)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            candidates = inbox.search(
                    new jakarta.mail.search.ReceivedDateTerm(jakarta.mail.search.ComparisonTerm.GE, since));
        } else if (freshStart) {
            // New mailbox, or the server reset UIDVALIDITY: re-baseline with a bounded
            // backfill of the most recent messages (never pull the whole history).
            candidates = mostRecentByUid(uf, inbox.getMessages(), backfillCount);
        } else {
            // Incremental: everything with UID strictly greater than the last processed,
            // regardless of read/unread; capped so a large backlog drains over cycles.
            candidates = filterAndCap(uf, uf.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID), lastUid);
        }

        if (candidates.length > 0) {
            FetchProfile fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(FetchProfile.Item.ENVELOPE);
            inbox.fetch(candidates, fp);
        }

        // On a fresh start, optionally ingest only messages within a recent time window
        // (e.g. the last 2 hours) so we don't fetch hundreds of old bodies. Messages
        // outside the window are still counted toward the UID cursor (so we baseline past
        // them) but their bodies are never fetched. The window uses the message's sent
        // Date header (already in the ENVELOPE — no extra round-trip).
        java.time.Instant sinceCutoff = (freshStart && backfillSinceHours > 0)
                ? java.time.Instant.now().minus(backfillSinceHours, java.time.temporal.ChronoUnit.HOURS)
                : null;

        long base = lastUid == null ? 0L : lastUid;
        long maxUid = base;
        List<RawMessage> out = new ArrayList<>(candidates.length);
        for (Message message : candidates) {
            long uid;
            try {
                uid = uf.getUID(message);
            } catch (MessagingException e) {
                // Can't even read the UID — skip without advancing (nothing to advance to).
                log.warn("IMAP: cannot read UID of a message in '{}', skipping: {}",
                        vendor.getRestaurantName(), e.getMessage());
                continue;
            }
            try {
                String from = firstAddress(message);
                String subject = Optional.ofNullable(message.getSubject()).orElse("");
                if (sinceCutoff == null || withinWindow(message, sinceCutoff)) {
                    String body = extractText(message);
                    String messageId = stableMessageId(message, subject);
                    out.add(new RawMessage(from, subject, body, messageId));
                }
                // else: older than the window — skip ingesting; the UID cursor still advances below.
            } catch (MessagingException e) {
                // Poison message: one bad envelope must NOT abort the whole fetch and
                // wedge this vendor forever (the UID watermark only advances after the
                // loop). Log + skip, but STILL advance the cursor past it below so the
                // next poll self-heals.
                log.warn("IMAP: skipping unreadable message uid={} for '{}': {}",
                        uid, vendor.getRestaurantName(), e.getMessage());
            }
            if (uid > maxUid) {
                maxUid = uid;
            }
        }

        // Persist the cursor on the vendor (syncVendor saves it). Advance only to the max
        // UID actually processed; if the mailbox was empty on a fresh start, baseline at
        // the folder's current max so we start from "now".
        vendor.setImapUidValidity(validity);
        if (maxUid > base) {
            vendor.setImapLastUid(maxUid);
        } else if (freshStart) {
            vendor.setImapLastUid(currentMaxUid(uf, inbox));
        }
        return out;
    }

    /** True when the message's sent date is at/after the cutoff (null/unreadable date → included). */
    private static boolean withinWindow(Message message, java.time.Instant cutoff) {
        try {
            java.util.Date sent = message.getSentDate();
            return sent == null || !sent.toInstant().isBefore(cutoff);
        } catch (MessagingException e) {
            return true; // can't read the date — don't drop a possible order
        }
    }

    /** The most recent {@code n} messages by UID, returned in ascending-UID order. */
    private Message[] mostRecentByUid(UIDFolder uf, Message[] msgs, int n) throws MessagingException {
        record M(long uid, Message m) { }
        List<M> list = new ArrayList<>(msgs.length);
        for (Message m : msgs) {
            list.add(new M(uf.getUID(m), m));
        }
        list.sort(Comparator.comparingLong(M::uid));
        int from = Math.max(0, list.size() - n);
        List<M> tail = list.subList(from, list.size());
        Message[] out = new Message[tail.size()];
        for (int i = 0; i < tail.size(); i++) {
            out[i] = tail.get(i).m();
        }
        return out;
    }

    /** Keep only UIDs strictly greater than the watermark, ascending, capped at maxPerFetch. */
    private Message[] filterAndCap(UIDFolder uf, Message[] msgs, long lastUid) throws MessagingException {
        record M(long uid, Message m) { }
        List<M> list = new ArrayList<>();
        for (Message m : msgs) {
            long uid = uf.getUID(m);
            if (uid > lastUid) {
                list.add(new M(uid, m));
            }
        }
        list.sort(Comparator.comparingLong(M::uid));
        if (list.size() > maxPerFetch) {
            list = new ArrayList<>(list.subList(0, maxPerFetch)); // oldest first
        }
        Message[] out = new Message[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).m();
        }
        return out;
    }

    /** Highest UID currently in the folder (0 when empty) — used to baseline an empty mailbox. */
    private long currentMaxUid(UIDFolder uf, Folder inbox) throws MessagingException {
        int count = inbox.getMessageCount();
        return count <= 0 ? 0L : uf.getUID(inbox.getMessage(count));
    }

    /** Connectivity/timeout errors are worth retrying; auth/protocol errors are not. */
    private boolean isTransient(MessagingException e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.net.ConnectException
                || cause instanceof java.net.UnknownHostException
                || cause instanceof java.io.IOException) {
            return true;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("connection") || msg.contains("reset");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- IMAP helpers (moved from the former ImapIngestionService) ----

    private String firstAddress(Message message) throws MessagingException {
        var from = message.getFrom();
        return (from != null && from.length > 0) ? from[0].toString() : "";
    }

    /** Prefer the Message-ID header; fall back to a hash of subject + sent date. */
    private String stableMessageId(Message message, String subject) throws MessagingException {
        if (message instanceof MimeMessage mime) {
            String id = mime.getMessageID();
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        long sent = message.getSentDate() != null ? message.getSentDate().getTime() : 0L;
        return "gen-" + Math.abs((subject + "|" + sent).hashCode());
    }

    private String extractText(Message message) {
        try {
            Object content = message.getContent();
            return extractFromContent(content, message.getContentType());
        } catch (IOException | MessagingException e) {
            log.debug("Failed to read message body: {}", e.getMessage());
            return "";
        }
    }

    private String extractFromContent(Object content, String contentType)
            throws IOException, MessagingException {
        if (content instanceof String text) {
            return isHtml(contentType) ? stripHtml(text) : text;
        }
        if (content instanceof Multipart multipart) {
            String html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                Object partContent = part.getContent();
                if (part.isMimeType("text/plain")) {
                    return String.valueOf(partContent);
                }
                if (part.isMimeType("text/html")) {
                    html = stripHtml(String.valueOf(partContent));
                } else if (partContent instanceof Multipart) {
                    String nested = extractFromContent(partContent, part.getContentType());
                    if (!nested.isBlank()) {
                        return nested;
                    }
                }
            }
            return html != null ? html : "";
        }
        return String.valueOf(content);
    }

    private boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/html");
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private void closeQuietly(Folder folder) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException ignored) {
                // best-effort close
            }
        }
    }

    private void closeQuietly(Store store) {
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // best-effort close
            }
        }
    }
}

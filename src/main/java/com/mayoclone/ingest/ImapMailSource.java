package com.mayoclone.ingest;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link MailSource} that pulls UNSEEN messages from a vendor's mailbox over
 * IMAP. Hardened:
 *
 * <ul>
 *   <li>TLS is enforced (protocol {@code imaps}) with server-identity verification
 *       ({@code mail.imaps.ssl.checkserveridentity=true}); plaintext IMAP is refused.</li>
 *   <li>Connection + read timeouts are set to bound a hung mailbox.</li>
 *   <li>Transient failures are retried with exponential backoff.</li>
 * </ul>
 */
@Component
public class ImapMailSource implements MailSource {

    private static final Logger log = LoggerFactory.getLogger(ImapMailSource.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;

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
        List<RawMessage> out = new ArrayList<>();
        try {
            // Always require TLS: refuse to send credentials over a plaintext connection.
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.checkserveridentity", "true");
            props.put("mail.imaps.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
            props.put("mail.imaps.timeout", String.valueOf(READ_TIMEOUT_MS));
            props.put("mail.imaps.writetimeout", String.valueOf(READ_TIMEOUT_MS));

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            String username = (vendor.getImapUsername() == null || vendor.getImapUsername().isBlank())
                    ? vendor.getOwnerEmail() : vendor.getImapUsername();
            store.connect(vendor.getImapHost(), vendor.getImapPort(), username, vendor.getImapPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (Message message : messages) {
                String from = firstAddress(message);
                String subject = Optional.ofNullable(message.getSubject()).orElse("");
                String body = extractText(message);
                String messageId = stableMessageId(message, subject);
                out.add(new RawMessage(from, subject, body, messageId));
            }
            return out;
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
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

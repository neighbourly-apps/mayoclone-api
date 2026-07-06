package com.mayoclone.service;

import com.mayoclone.domain.MailAccount;
import com.mayoclone.domain.OrderRecord;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.parser.AggregatorEmailParser;
import com.mayoclone.parser.ParsedOrder;
import com.mayoclone.repository.MailAccountRepository;
import com.mayoclone.repository.OrderRecordRepository;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Connects to mailboxes over IMAP, extracts order emails, runs them through the
 * registered parsers, and stores the resulting orders (deduplicated).
 *
 * <p>{@link #ingestRaw} exposes the parse -> store path without IMAP so the
 * demo endpoint (and tests) can exercise the full pipeline with no real mailbox.
 */
@Service
public class ImapIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ImapIngestionService.class);

    private final List<AggregatorEmailParser> parsers;
    private final OrderRecordRepository orderRepo;
    private final MailAccountRepository accountRepo;

    public ImapIngestionService(List<AggregatorEmailParser> parsers,
                                OrderRecordRepository orderRepo,
                                MailAccountRepository accountRepo) {
        this.parsers = parsers;
        this.orderRepo = orderRepo;
        this.accountRepo = accountRepo;
    }

    /** Sync a single account: fetch UNSEEN messages, parse, and store new orders. */
    public IngestResult syncAccount(MailAccount account) {
        Store store = null;
        Folder inbox = null;
        int fetched = 0;
        int newOrders = 0;
        try {
            Properties props = new Properties();
            String protocol = account.isUseSsl() ? "imaps" : "imap";
            props.put("mail.store.protocol", protocol);

            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(account.getImapHost(), account.getImapPort(),
                    account.getUsername(), account.getPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            // Only look at messages that haven't been read yet.
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (Message message : messages) {
                fetched++;
                String from = firstAddress(message);
                String subject = Optional.ofNullable(message.getSubject()).orElse("");
                String body = extractText(message);
                String messageId = stableMessageId(message, subject);

                if (ingestRaw(from, subject, body, messageId).newOrders() > 0) {
                    newOrders++;
                }
            }

            account.setLastSyncedAt(Instant.now());
            accountRepo.save(account);
            return new IngestResult(fetched, newOrders);
        } catch (MessagingException e) {
            // Translate low-level mail errors into a clean, actionable message.
            throw new RuntimeException(
                    "IMAP sync failed for account '" + account.getLabel() + "' ("
                            + account.getImapHost() + ":" + account.getImapPort() + "): "
                            + e.getMessage(), e);
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
    }

    /** Sync every active account, aggregating the results. */
    public IngestResult syncAllActive() {
        IngestResult total = IngestResult.empty();
        for (MailAccount account : accountRepo.findByActiveTrue()) {
            try {
                total = total.plus(syncAccount(account));
            } catch (RuntimeException e) {
                // One bad mailbox shouldn't abort the whole sweep.
                log.warn("Skipping account {}: {}", account.getLabel(), e.getMessage());
            }
        }
        return total;
    }

    /**
     * Run the parse -> dedup -> store path for a single raw email, bypassing IMAP.
     * Returns fetched=1 always; newOrders=1 if a fresh order was stored, else 0.
     */
    public IngestResult ingestRaw(String from, String subject, String body, String messageId) {
        Optional<AggregatorEmailParser> parser = parsers.stream()
                .filter(p -> p.supports(from, subject))
                .findFirst();
        if (parser.isEmpty()) {
            log.debug("No parser matched email from '{}' subject '{}'", from, subject);
            return new IngestResult(1, 0);
        }

        ParsedOrder parsed = parser.get().parse(from, subject, body, messageId);

        // Dedup 1: same source email already ingested.
        if (parsed.sourceMessageId() != null
                && orderRepo.existsBySourceMessageId(parsed.sourceMessageId())) {
            return new IngestResult(1, 0);
        }
        // Dedup 2: same (aggregator, externalOrderId) already stored.
        if (orderRepo.existsByAggregatorAndExternalOrderId(
                parsed.aggregator(), parsed.externalOrderId())) {
            return new IngestResult(1, 0);
        }

        orderRepo.save(toEntity(parsed));
        return new IngestResult(1, 1);
    }

    private OrderRecord toEntity(ParsedOrder p) {
        OrderRecord o = new OrderRecord();
        o.setAggregator(p.aggregator());
        o.setExternalOrderId(p.externalOrderId());
        o.setCustomerName(p.customerName() != null ? p.customerName() : "Unknown");
        o.setAmount(p.amount());
        o.setCurrency(p.currency() != null ? p.currency() : "INR");
        o.setPlacedAt(p.placedAt() != null ? p.placedAt() : Instant.now());
        o.setStatus(p.status() != null ? p.status() : "RECEIVED");
        o.setSubject(p.subject());
        o.setSourceMessageId(p.sourceMessageId());
        o.setItems(p.items());
        o.setCreatedAt(Instant.now());
        return o;
    }

    // ---- IMAP helpers ----

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

    /** Walk a (possibly multipart) message and return its best-effort text body. */
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
                    return String.valueOf(partContent); // plain text preferred
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

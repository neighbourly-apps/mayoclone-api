package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.ingest.MailSource;
import com.mayoclone.ingest.RawMessage;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link MailSource} that pulls aggregator mail from a connected Gmail mailbox via
 * the Gmail REST API (readonly). Flow: refresh an access token, {@code
 * users.messages.list} with a {@code from:(...)} query built from active
 * aggregator sender domains, then {@code users.messages.get?format=RAW} per hit,
 * decoded into a {@link RawMessage}.
 *
 * <p>All Google calls are live and are NEVER exercised by the build. When Gmail
 * is not configured, or the vendor has no refresh token, {@link #fetch} returns an
 * empty list so a scheduled sweep degrades gracefully.
 */
@Component
public class GmailMailSource implements MailSource {

    private static final Logger log = LoggerFactory.getLogger(GmailMailSource.class);
    private static final int MAX_MESSAGES = 25;

    private final GmailOAuthService oauth;

    public GmailMailSource(GmailOAuthService oauth) {
        this.oauth = oauth;
    }

    @Override
    public MailSourceType type() {
        return MailSourceType.GMAIL_OAUTH;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RawMessage> fetch(Vendor vendor) {
        List<RawMessage> out = new ArrayList<>();
        if (!oauth.isEnabled() || vendor.getOauthRefreshToken() == null) {
            return out; // feature off or mailbox not connected — nothing to pull
        }
        try {
            String accessToken = oauth.refreshAccessToken(vendor.getOauthRefreshToken());
            if (accessToken == null) {
                return out;
            }
            String query = oauth.buildGmailQuery();
            String listUrl = GmailOAuthConfig.GMAIL_API_BASE
                    + "/users/me/messages?maxResults=" + MAX_MESSAGES
                    + (query.isBlank() ? "" : "&q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));

            Map<String, Object> listing = oauth.restClient().get()
                    .uri(listUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

            List<Map<String, Object>> ids = listing == null
                    ? List.of() : (List<Map<String, Object>>) listing.getOrDefault("messages", List.of());
            for (Map<String, Object> ref : ids) {
                String id = String.valueOf(ref.get("id"));
                RawMessage rm = fetchOne(accessToken, id);
                if (rm != null) {
                    out.add(rm);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Gmail fetch failed for vendor {}: {}", vendor.getRestaurantName(), e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private RawMessage fetchOne(String accessToken, String id) {
        try {
            Map<String, Object> msg = oauth.restClient().get()
                    .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/messages/" + id + "?format=RAW")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);
            if (msg == null || msg.get("raw") == null) {
                return null;
            }
            byte[] rfc822 = Base64.getUrlDecoder().decode(String.valueOf(msg.get("raw")));
            return parseRfc822(rfc822, id);
        } catch (RuntimeException e) {
            log.debug("Gmail message {} fetch/parse failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Decode an RFC-822 byte stream into a {@link RawMessage}. */
    private RawMessage parseRfc822(byte[] bytes, String gmailId) {
        try {
            Session session = Session.getInstance(new Properties());
            MimeMessage mime = new MimeMessage(session, new ByteArrayInputStream(bytes));
            String from = (mime.getFrom() != null && mime.getFrom().length > 0)
                    ? mime.getFrom()[0].toString() : "";
            String subject = Optional.ofNullable(mime.getSubject()).orElse("");
            String messageId = mime.getMessageID() != null ? mime.getMessageID() : "gmail-" + gmailId;
            String body = extractText(mime);
            return new RawMessage(from, subject, body, messageId);
        } catch (Exception e) {
            log.debug("Failed to parse Gmail RFC822 for {}: {}", gmailId, e.getMessage());
            return null;
        }
    }

    private String extractText(Message message) {
        try {
            Object content = message.getContent();
            if (content instanceof String s) {
                return message.isMimeType("text/html") ? stripHtml(s) : s;
            }
            if (content instanceof Multipart mp) {
                String html = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    var part = mp.getBodyPart(i);
                    Object pc = part.getContent();
                    if (part.isMimeType("text/plain")) {
                        return String.valueOf(pc);
                    }
                    if (part.isMimeType("text/html")) {
                        html = stripHtml(String.valueOf(pc));
                    }
                }
                return html != null ? html : "";
            }
            return String.valueOf(content);
        } catch (Exception e) {
            return "";
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }
}

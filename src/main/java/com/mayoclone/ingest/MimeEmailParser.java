package com.mayoclone.ingest;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a raw RFC822 (MIME) email — as a {@code String} or {@link InputStream} —
 * into the source-agnostic {@link RawMessage} the ingestion pipeline consumes.
 *
 * <p>Extraction rules:
 * <ul>
 *   <li><b>From</b>: the first address of the {@code From} header (bare address,
 *       e.g. {@code orders@zoopindia.com}).</li>
 *   <li><b>Subject</b>: the decoded {@code Subject} header.</li>
 *   <li><b>Body</b>: a text body found by walking any multipart tree, preferring
 *       {@code text/plain}; if only {@code text/html} exists it is stripped to
 *       plain text.</li>
 *   <li><b>Message-ID</b>: the {@code Message-ID} header, falling back to a stable
 *       hash of {@code subject + from} when the header is absent.</li>
 * </ul>
 *
 * <p>Robust to malformed input: parse errors are wrapped in a {@link MimeParseException}
 * (a clean, unchecked exception) so callers can dead-letter instead of crashing.
 * This is a stateless {@code @Component} (also usable statically via {@link #parse}).
 */
@Component
public class MimeEmailParser {

    private static final Logger log = LoggerFactory.getLogger(MimeEmailParser.class);

    /** A single shared, credential-less javax.mail session — MimeMessage only needs it to exist. */
    private static final Session SESSION = Session.getInstance(new Properties());

    /** Thrown when a raw email cannot be parsed into even a best-effort RawMessage. */
    public static class MimeParseException extends RuntimeException {
        public MimeParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Parse a raw RFC822 email from a String. */
    public RawMessage parse(String rawEmail) {
        byte[] bytes = (rawEmail == null ? "" : rawEmail).getBytes(StandardCharsets.UTF_8);
        return parse(new ByteArrayInputStream(bytes));
    }

    /** Parse a raw RFC822 email from an InputStream. The stream is fully consumed. */
    public RawMessage parse(InputStream rawEmail) {
        try {
            MimeMessage message = new MimeMessage(SESSION, rawEmail);
            String from = firstAddress(message);
            String subject = safe(message.getSubject());
            String body = extractBody(message);
            String messageId = extractMessageId(message, subject, from);
            return new RawMessage(from, subject, body, messageId);
        } catch (Exception e) {
            throw new MimeParseException("Failed to parse RFC822 email: " + e.getMessage(), e);
        }
    }

    private static String firstAddress(MimeMessage message) {
        try {
            Address[] from = message.getFrom();
            if (from != null && from.length > 0) {
                if (from[0] instanceof InternetAddress ia && ia.getAddress() != null) {
                    return ia.getAddress();
                }
                return from[0].toString();
            }
        } catch (Exception e) {
            log.debug("Could not read From header: {}", e.getMessage());
        }
        return null;
    }

    private static String extractMessageId(MimeMessage message, String subject, String from) {
        try {
            String[] ids = message.getHeader("Message-ID");
            if (ids != null && ids.length > 0 && ids[0] != null && !ids[0].isBlank()) {
                return ids[0].trim();
            }
        } catch (Exception e) {
            log.debug("Could not read Message-ID header: {}", e.getMessage());
        }
        // Deterministic fallback so dedup/idempotency still has a stable key.
        int h = (safe(subject) + "|" + safe(from)).hashCode();
        return "<mime-" + Integer.toHexString(h) + "@mayoclone.local>";
    }

    /**
     * Walk the MIME tree and return the best text body: the first {@code text/plain}
     * found (depth-first), else the first {@code text/html} stripped to plain text.
     */
    private static String extractBody(Part part) throws Exception {
        String plain = findByType(part, "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = findByType(part, "text/html");
        if (html != null && !html.isBlank()) {
            return stripHtml(html);
        }
        // Last resort: a non-multipart part whose content is a String.
        Object content = part.getContent();
        if (content instanceof String s) {
            return part.isMimeType("text/html") ? stripHtml(s) : s;
        }
        return "";
    }

    /** Depth-first search for the first part matching the given MIME type; returns its String content. */
    private static String findByType(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
        }
        Object content;
        try {
            content = part.getContent();
        } catch (Exception e) {
            return null; // unparseable sub-part — skip
        }
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                // Skip explicit attachments — we only want inline text bodies.
                Part sub = mp.getBodyPart(i);
                String disp = safe(sub.getDisposition());
                if (Part.ATTACHMENT.equalsIgnoreCase(disp)) {
                    continue;
                }
                String found = findByType(sub, mimeType);
                if (found != null && !found.isBlank()) {
                    return found;
                }
            }
        }
        return null;
    }

    // --- HTML → text ---------------------------------------------------------

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern BLOCK_BREAK =
            Pattern.compile("(?i)<\\s*(br\\s*/?|/p|/div|/tr|/li|/h[1-6])\\s*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");

    /**
     * Best-effort HTML → plain text: drop script/style, turn common block-closing
     * tags into newlines, strip all remaining tags, and decode a handful of common
     * entities. Good enough to run the IRCTC regex parsers over an HTML-only email.
     */
    public static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = BLOCK_BREAK.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&rupee;", "₹");
        // Decode ALL numeric HTML entities — decimal &#8377; AND hex &#x20b9; (both are ₹,
        // real IRCTC/aggregator emails use the hex form). Without this, currency symbols
        // survive as "&#x20b9;" and every amount/price regex misses, so amounts read as 0.
        s = decodeNumericEntities(s);
        // Collapse trailing spaces per line and squeeze runs of blank lines.
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n")) {
            out.append(line.strip()).append('\n');
        }
        return MANY_BLANK_LINES.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x)?([0-9a-fA-F]+);");

    /** Decode {@code &#NNNN;} (decimal) and {@code &#xHHHH;} (hex) entities to their characters. */
    static String decodeNumericEntities(String s) {
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            String repl = m.group();
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1) != null ? 16 : 10);
                if (cp > 0 && cp <= 0x10FFFF) {
                    repl = new String(Character.toChars(cp));
                }
            } catch (NumberFormatException ignored) {
                // leave the original token in place
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}

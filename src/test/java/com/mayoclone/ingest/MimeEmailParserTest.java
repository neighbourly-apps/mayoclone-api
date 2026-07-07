package com.mayoclone.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the RFC822 MIME → RawMessage utility. */
class MimeEmailParserTest {

    private final MimeEmailParser parser = new MimeEmailParser();

    @Test
    void extractsFromSubjectPlainBodyAndMessageIdFromMultipart() {
        String eml = """
                From: Zoop Orders <orders@zoopindia.com>
                To: vendor-abc@inbound.mayoclone.local
                Subject: Your order is confirmed
                Message-ID: <abc-123@zoopindia.com>
                MIME-Version: 1.0
                Content-Type: multipart/alternative; boundary="B1"

                --B1
                Content-Type: text/plain; charset=UTF-8

                PNR: 4512367890
                Total: Rs. 360
                --B1
                Content-Type: text/html; charset=UTF-8

                <html><body><p>PNR: 4512367890</p></body></html>
                --B1--
                """;

        RawMessage msg = parser.parse(eml);

        assertEquals("orders@zoopindia.com", msg.from());
        assertEquals("Your order is confirmed", msg.subject());
        assertEquals("<abc-123@zoopindia.com>", msg.messageId());
        assertTrue(msg.body().contains("PNR: 4512367890"), "should prefer the text/plain part");
        assertTrue(msg.body().contains("Total: Rs. 360"));
    }

    @Test
    void fallsBackToStrippedHtmlWhenNoPlainPart() {
        String eml = """
                From: care@comesum.com
                Subject: HTML only
                Message-ID: <html-only@comesum.com>
                MIME-Version: 1.0
                Content-Type: text/html; charset=UTF-8

                <html><body><h2>Order</h2><p>PNR: 1122334455</p><p>Total: &#8377;250</p></body></html>
                """;

        RawMessage msg = parser.parse(eml);

        assertTrue(msg.body().contains("PNR: 1122334455"), "html should be stripped to text");
        assertTrue(msg.body().contains("₹250"), "entity should be decoded");
        assertTrue(msg.body().indexOf('<') < 0, "no raw tags should remain");
    }

    @Test
    void synthesizesMessageIdWhenHeaderAbsent() {
        String eml = """
                From: orders@railrestro.com
                Subject: No message id here

                Body text only.
                """;

        RawMessage msg = parser.parse(eml);

        assertNotNull(msg.messageId());
        assertTrue(msg.messageId().startsWith("<mime-"), "should synthesize a stable fallback id");
        // Deterministic: same input → same fallback id.
        assertEquals(msg.messageId(), parser.parse(eml).messageId());
    }

    @Test
    void malformedInputThrowsCleanException() {
        // A stream that fails mid-read must surface as our clean wrapper, not a raw IOException.
        java.io.InputStream boom = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };
        assertThrows(MimeEmailParser.MimeParseException.class, () -> parser.parse(boom));
    }
}

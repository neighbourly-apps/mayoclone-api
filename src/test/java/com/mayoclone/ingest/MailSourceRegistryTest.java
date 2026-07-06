package com.mayoclone.ingest;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MailSource selection: IMAP/GMAIL_OAUTH resolve; FORWARDING (push-only) does not. */
class MailSourceRegistryTest {

    private static MailSource stub(MailSourceType type) {
        return new MailSource() {
            @Override
            public MailSourceType type() {
                return type;
            }

            @Override
            public List<RawMessage> fetch(Vendor v) {
                return List.of();
            }
        };
    }

    private final MailSourceRegistry registry = new MailSourceRegistry(
            List.of(stub(MailSourceType.IMAP), stub(MailSourceType.GMAIL_OAUTH)));

    private static Vendor vendorOf(MailSourceType type) {
        Vendor v = new Vendor();
        v.setSourceType(type);
        return v;
    }

    @Test
    void resolvesImapSource() {
        var src = registry.forVendor(vendorOf(MailSourceType.IMAP));
        assertTrue(src.isPresent());
        assertEquals(MailSourceType.IMAP, src.get().type());
    }

    @Test
    void resolvesGmailSource() {
        var src = registry.forVendor(vendorOf(MailSourceType.GMAIL_OAUTH));
        assertTrue(src.isPresent());
        assertEquals(MailSourceType.GMAIL_OAUTH, src.get().type());
    }

    @Test
    void forwardingHasNoPullSource() {
        assertTrue(registry.forVendor(vendorOf(MailSourceType.FORWARDING)).isEmpty());
    }

    @Test
    void legacyNullSourceTypeDefaultsToImap() {
        Vendor v = new Vendor();
        v.setSourceType(null); // getSourceType() coerces null -> IMAP
        assertTrue(registry.forVendor(v).isPresent());
        assertEquals(MailSourceType.IMAP, registry.forVendor(v).get().type());
    }
}

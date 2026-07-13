package com.mayoclone.ingest;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mayoclone.domain.Vendor;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves IMAP fetching is READ-AGNOSTIC against a real (in-process) IMAP server:
 * an email already marked read by "another app" is still captured, we never re-fetch,
 * and we never mark anything read ourselves. No Docker — GreenMail runs in-process.
 */
class ImapMailSourceGreenMailTest {

    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String USER = "box@test.local";
    private static final String PASS = "secret";

    private final ImapMailSource source = new ImapMailSource(30, 200, 0);
    private int delivered = 0;

    @BeforeEach
    void setUp() {
        greenMail.setUser(USER, USER, PASS);
    }

    @Test
    void alreadyReadMessagesAreStillFetched_andNeverRefetched_andWeNeverMarkSeen() throws Exception {
        Vendor vendor = new Vendor(); // fresh mailbox: imapLastUid/uidValidity null

        // 1) Deliver 3 order emails, first fetch returns all 3 + advances the UID cursor.
        send("A"); send("B"); send("C");
        List<RawMessage> r1 = fetch(vendor);
        assertEquals(Set.of("A", "B", "C"), subjects(r1), "first fetch returns all delivered mail");
        assertNotNull(vendor.getImapLastUid(), "UID watermark set");
        assertNotNull(vendor.getImapUidValidity(), "UIDVALIDITY recorded");

        // 2) Another app reads "B" (marks it \Seen). Deliver 2 more.
        markSeen("B");
        send("D"); send("E");

        // Second fetch returns ONLY the 2 NEW ones — NOT the read "B", NOT the already-seen A/C.
        List<RawMessage> r2 = fetch(vendor);
        assertEquals(Set.of("D", "E"), subjects(r2),
                "read state is irrelevant: only new UIDs returned, nothing re-fetched");

        // 3) The crux: a message read BEFORE our very first fetch must still be captured.
        Vendor fresh = new Vendor();
        send("F");
        markSeen("F"); // read by someone else before we ever look
        List<RawMessage> r3 = fetch(fresh);
        assertTrue(subjects(r3).contains("F"),
                "an already-read message on first sync is NOT missed");

        // 4) We open read-only: messages we (only) read are still UNSEEN on the server.
        assertFalse(isSeenOnServer("A"), "we must never mark mail as read (A stays unseen)");
        assertFalse(isSeenOnServer("D"), "D stays unseen after our read-only fetch");
    }

    // ---- helpers ----

    private List<RawMessage> fetch(Vendor vendor) throws Exception {
        Store store = imapStore();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                return source.collect(vendor, inbox);
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    private void send(String subject) {
        GreenMailUtil.sendTextEmailTest(USER, "orders@zoopindia.com", subject,
                "PNR: 4455667788\nTrain No.: 12951 Rajdhani\nDelivery Station: BPL Bhopal\nTotal: Rs.360");
        delivered++;
        greenMail.waitForIncomingEmail(5000, delivered);
    }

    private void markSeen(String subject) throws Exception {
        Store store = imapStore();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            try {
                for (Message m : inbox.getMessages()) {
                    if (subject.equals(m.getSubject())) {
                        m.setFlag(Flags.Flag.SEEN, true);
                    }
                }
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    private boolean isSeenOnServer(String subject) throws Exception {
        Store store = imapStore();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                for (Message m : inbox.getMessages()) {
                    if (subject.equals(m.getSubject())) {
                        return m.isSet(Flags.Flag.SEEN);
                    }
                }
                return false;
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    private Store imapStore() throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.peek", "true"); // mirror production: read via BODY.PEEK, never set \Seen
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), USER, PASS);
        return store;
    }

    private static Set<String> subjects(List<RawMessage> msgs) {
        return msgs.stream().map(RawMessage::subject).collect(Collectors.toSet());
    }
}

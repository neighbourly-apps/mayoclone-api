package com.mayoclone.ingest;

import com.mayoclone.domain.Vendor;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A single message whose envelope read throws MessagingException must NOT abort the
 * whole fetch or wedge the vendor: it is skipped, the good messages are returned, and
 * the UID watermark still advances PAST the bad message so the next poll self-heals.
 */
class ImapMailSourcePoisonMessageTest {

    /** A Folder that is also a UIDFolder, so Mockito can mock both in one object. */
    abstract static class UidFolder extends Folder implements UIDFolder {
        protected UidFolder() {
            super(null);
        }
    }

    @Test
    void poisonMessageIsSkippedAndCursorStillAdvances() throws Exception {
        ImapMailSource source = new ImapMailSource(30, 200, 0);

        MimeMessage good = mock(MimeMessage.class);
        MimeMessage poison = mock(MimeMessage.class);

        UidFolder folder = mock(UidFolder.class);
        when(folder.getUIDValidity()).thenReturn(1L);
        Message[] all = {good, poison};
        when(folder.getMessages()).thenReturn(all);
        when(folder.getUID(good)).thenReturn(10L);
        when(folder.getUID(poison)).thenReturn(11L);

        // Good message: a readable envelope + body.
        when(good.getFrom()).thenReturn(new jakarta.mail.Address[]{new InternetAddress("orders@zoopindia.com")});
        when(good.getSubject()).thenReturn("Good order");
        when(good.getContent()).thenReturn("PNR 12345");
        when(good.getContentType()).thenReturn("text/plain");
        when(good.getMessageID()).thenReturn("<good@id>");

        // Poison message: envelope read blows up mid-assembly.
        when(poison.getFrom()).thenReturn(new jakarta.mail.Address[]{new InternetAddress("x@y.com")});
        doThrow(new MessagingException("corrupt envelope")).when(poison).getSubject();

        Vendor vendor = new Vendor(); // fresh mailbox → freshStart backfill path

        List<RawMessage> out = source.collect(vendor, folder);

        assertEquals(1, out.size(), "only the good message is returned; poison is skipped");
        assertEquals("Good order", out.get(0).subject());
        // Crux: cursor advanced past the poison UID (11), so the vendor isn't wedged.
        assertEquals(11L, vendor.getImapLastUid(),
                "UID watermark advances past the bad message so polling self-heals");
    }
}

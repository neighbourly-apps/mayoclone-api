package com.mayoclone.security.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmStringConverterTest {

    private static final String KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="; // 32 bytes

    private AesGcmStringConverter converter() {
        SecretKey key = AesGcmStringConverter.toKey(KEY_B64);
        return new AesGcmStringConverter(key);
    }

    @Test
    void roundTripsAValue() {
        AesGcmStringConverter c = converter();
        String plaintext = "s3cr3t-imap-password!";
        String stored = c.convertToDatabaseColumn(plaintext);

        assertNotEquals(plaintext, stored, "stored form must not equal plaintext");
        assertEquals(plaintext, c.convertToEntityAttribute(stored));
    }

    @Test
    void producesDifferentCiphertextEachTime() {
        AesGcmStringConverter c = converter();
        String a = c.convertToDatabaseColumn("same-value");
        String b = c.convertToDatabaseColumn("same-value");
        assertNotEquals(a, b, "random IV must make ciphertexts differ");
        // ...but both decrypt back to the original.
        assertEquals("same-value", c.convertToEntityAttribute(a));
        assertEquals("same-value", c.convertToEntityAttribute(b));
    }

    @Test
    void handlesNull() {
        AesGcmStringConverter c = converter();
        assertNull(c.convertToDatabaseColumn(null));
        assertNull(c.convertToEntityAttribute(null));
    }

    @Test
    void rejectsWrongSizedKey() {
        assertTrue(assertThrowsIllegalState(() ->
                AesGcmStringConverter.toKey("c2hvcnQ=")).contains("32 bytes"));
    }

    private static String assertThrowsIllegalState(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected IllegalStateException");
    }
}

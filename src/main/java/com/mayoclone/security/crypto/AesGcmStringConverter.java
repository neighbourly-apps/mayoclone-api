package com.mayoclone.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Transparent AES-256-GCM encryption for sensitive String columns (e.g.
 * {@code Vendor.imapPassword}; reserved for future OAuth tokens). Apply with
 * {@code @Convert(converter = AesGcmStringConverter.class)}.
 *
 * <p>Scheme: a fresh random 12-byte IV is generated per value; the stored form is
 * {@code base64(IV || ciphertext||GCM-tag)}. The 256-bit key comes from env
 * {@code MAYOCLONE_ENC_KEY} (base64 of 32 bytes). If unset, a well-known DEV-ONLY
 * default is used and a warning is logged — prod MUST set a real key.
 *
 * <p>The key is resolved from the environment (not Spring) because JPA converters
 * are instantiated by Hibernate. A package-private constructor lets tests inject a
 * key directly.
 */
@Converter
public class AesGcmStringConverter implements AttributeConverter<String, String> {

    static final String ENV_KEY = "MAYOCLONE_ENC_KEY";
    // DEV-ONLY fallback (exactly 32 bytes, base64). NEVER use in production.
    private static final String DEV_DEFAULT_KEY_B64 = "bWF5b2Nsb25lLWRldi1kZWZhdWx0LWVuYy1rZXktMzI=";

    private static final int IV_LENGTH = 12;      // 96-bit nonce, recommended for GCM
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    /** Used by Hibernate — resolves the key from the environment. */
    public AesGcmStringConverter() {
        this(resolveKeyFromEnv());
    }

    /** Used by tests — inject a specific key. */
    AesGcmStringConverter(SecretKey key) {
        this.key = key;
    }

    private static SecretKey resolveKeyFromEnv() {
        String b64 = System.getenv(ENV_KEY);
        if (b64 == null || b64.isBlank()) {
            b64 = System.getProperty(ENV_KEY, "");
        }
        if (b64 == null || b64.isBlank()) {
            // No key configured — fall back to the DEV default (logged as a warning by callers).
            b64 = DEV_DEFAULT_KEY_B64;
        }
        return toKey(b64);
    }

    static SecretKey toKey(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    ENV_KEY + " must decode to exactly 32 bytes (AES-256); got " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never include the plaintext in the message.
            throw new IllegalStateException("Failed to encrypt column value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt column value", e);
        }
    }
}

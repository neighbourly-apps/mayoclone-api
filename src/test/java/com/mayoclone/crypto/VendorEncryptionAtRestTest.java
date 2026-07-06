package com.mayoclone.crypto;

import com.mayoclone.domain.Account;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encryption-at-rest for the sensitive Vendor columns. The AES-256-GCM
 * {@link com.mayoclone.security.crypto.AesGcmStringConverter} must store ciphertext
 * in {@code imap_password} / {@code oauth_refresh_token} while the JPA entity
 * round-trips transparently back to plaintext.
 */
class VendorEncryptionAtRestTest extends AbstractIntegrationTest {

    private static final String PLAINTEXT_IMAP = "super-secret-imap-pw-123";
    private static final String PLAINTEXT_OAUTH = "1//0gRefreshTokenValue-abcXYZ";

    @Autowired
    private VendorRepository vendorRepo;

    @Autowired
    private AccountRepository accountRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Test
    void secretsAreCiphertextInTheColumnButPlaintextThroughTheEntity() {
        Account account = accountRepo.save(newAccount());

        Vendor v = new Vendor();
        v.setAccountId(account.getId());
        v.setRestaurantName("Crypto Foods");
        v.setOwnerEmail("owner@test.local");
        v.setStationCode("NDLS");
        v.setPhone("9876543210");
        v.setSourceType(MailSourceType.IMAP);
        v.setImapHost("imap.test.local");
        v.setImapPort(993);
        v.setImapUsername("owner@test.local");
        v.setImapPassword(PLAINTEXT_IMAP);
        v.setOauthRefreshToken(PLAINTEXT_OAUTH);
        v.setUseSsl(true);
        v.setActive(true);
        v.setCreatedAt(Instant.now());
        Long id = vendorRepo.save(v).getId();

        // Force the INSERT to hit the DB and drop the first-level cache so the
        // read below genuinely re-materialises from the stored columns.
        em.flush();
        em.clear();

        // Raw column read (bypasses the JPA converter): must NOT be the plaintext.
        String rawImap = jdbc.queryForObject(
                "SELECT imap_password FROM vendor WHERE id = ?", String.class, id);
        String rawOauth = jdbc.queryForObject(
                "SELECT oauth_refresh_token FROM vendor WHERE id = ?", String.class, id);

        assertNotNull(rawImap);
        assertNotNull(rawOauth);
        assertNotEquals(PLAINTEXT_IMAP, rawImap, "imap_password must be encrypted at rest");
        assertNotEquals(PLAINTEXT_OAUTH, rawOauth, "oauth_refresh_token must be encrypted at rest");
        assertFalseContains(rawImap, PLAINTEXT_IMAP);
        assertFalseContains(rawOauth, PLAINTEXT_OAUTH);
        // Stored form is base64(IV||ciphertext||tag) — strictly longer than the plaintext.
        assertTrue(rawImap.length() > PLAINTEXT_IMAP.length());

        // Through the entity, the converter transparently decrypts back to plaintext.
        Vendor reloaded = vendorRepo.findById(id).orElseThrow();
        assertEquals(PLAINTEXT_IMAP, reloaded.getImapPassword());
        assertEquals(PLAINTEXT_OAUTH, reloaded.getOauthRefreshToken());
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertTrue(!haystack.contains(needle), "ciphertext must not contain the plaintext");
    }

    private Account newAccount() {
        Account a = new Account();
        a.setBusinessName("Crypto Biz");
        a.setEmail(uniqueEmail());
        a.setPasswordHash("noop");
        a.setRole(Account.ROLE_OWNER);
        a.setStatus(Account.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());
        return a;
    }
}

package com.mayoclone.service;

import com.mayoclone.domain.RefreshToken;
import com.mayoclone.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private RefreshTokenRepository repo;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repo = mock(RefreshTokenRepository.class);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new RefreshTokenService(repo, mock(AuditService.class), 604800);
    }

    @Test
    void issueNewFamilyPersistsHashedTokenAndReturnsRaw() {
        RefreshTokenService.Issued issued = service.issueNewFamily(7L);

        assertEquals(7L, issued.accountId());
        assertNotNull(issued.rawToken());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(captor.capture());
        RefreshToken saved = captor.getValue();
        // The raw token is NEVER stored — only its SHA-256 hash.
        assertEquals(RefreshTokenService.sha256(issued.rawToken()), saved.getTokenHash());
        assertTrue(saved.getTokenHash().length() == 64);
        assertEquals(7L, saved.getAccountId());
    }

    @Test
    void rotateRevokesOldAndIssuesNewInSameFamily() {
        RefreshToken existing = token(3L, "fam-1", false, Instant.now().plusSeconds(1000));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(existing));

        RefreshTokenService.Issued rotated = service.rotate("raw-token-value");

        assertEquals(3L, rotated.accountId());
        assertTrue(existing.isRevoked(), "old token must be revoked on rotation");
        // saved twice: the revoked old one + the new one
        verify(repo, times(2)).save(any(RefreshToken.class));
    }

    @Test
    void reuseOfRevokedTokenRevokesWholeFamily() {
        RefreshToken revoked = token(9L, "fam-theft", true, Instant.now().plusSeconds(1000));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.rotate("stolen"));
        assertEquals(401, ex.getStatusCode().value());
        verify(repo).revokeFamily("fam-theft");
    }

    @Test
    void unknownTokenIsUnauthorized() {
        when(repo.findByTokenHash(any())).thenReturn(Optional.empty());
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.rotate("nope"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void expiredTokenIsUnauthorized() {
        RefreshToken expired = token(1L, "fam", false, Instant.now().minusSeconds(10));
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(expired));
        assertThrows(ResponseStatusException.class, () -> service.rotate("old"));
    }

    private static RefreshToken token(Long accountId, String family, boolean revoked, Instant expires) {
        RefreshToken t = new RefreshToken();
        t.setAccountId(accountId);
        t.setFamilyId(family);
        t.setRevoked(revoked);
        t.setExpiresAt(expires);
        t.setCreatedAt(Instant.now());
        t.setTokenHash("hash");
        return t;
    }
}

package com.mayoclone.ingest.gmail;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure URL/query building for the Gmail flow — no network. */
class GmailOAuthServiceTest {

    @Test
    void consentUrlContainsRequiredOAuthParams() {
        String url = GmailOAuthService.buildAuthorizationUrl(
                "client-123.apps.googleusercontent.com",
                "https://api.mayoclone.app/api/mail/gmail/callback",
                "signed-state-value");

        assertTrue(url.startsWith(GmailOAuthConfig.AUTH_ENDPOINT));
        assertTrue(url.contains("client_id=client-123.apps.googleusercontent.com"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("prompt=consent"));
        assertTrue(url.contains("state=signed-state-value"));
        // Scope is URL-encoded.
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly"));
    }

    @Test
    void gmailQueryIsBuiltFromActiveSenderDomainsSortedAndDeduped() {
        AggregatorRepository aggRepo = mock(AggregatorRepository.class);
        when(aggRepo.findByActiveTrue()).thenReturn(List.of(
                agg("railrestro.com"),
                agg("zoopindia.com", "railrestro.com"))); // duplicate domain

        GmailOAuthService svc = new GmailOAuthService(
                new GmailOAuthConfig("id", "secret", "uri", "http://localhost:5173"),
                new OAuthStateSigner("s", 600),
                mock(VendorRepository.class),
                aggRepo,
                mock(AuditService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        assertEquals("from:(railrestro.com OR zoopindia.com)", svc.buildGmailQuery());
    }

    @Test
    void gmailQueryIsEmptyWhenNoDomains() {
        AggregatorRepository aggRepo = mock(AggregatorRepository.class);
        when(aggRepo.findByActiveTrue()).thenReturn(List.of());
        GmailOAuthService svc = new GmailOAuthService(
                new GmailOAuthConfig("id", "secret", "uri", "http://localhost:5173"),
                new OAuthStateSigner("s", 600),
                mock(VendorRepository.class), aggRepo, mock(AuditService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        assertEquals("", svc.buildGmailQuery());
    }

    private static Aggregator agg(String... domains) {
        Aggregator a = new Aggregator();
        a.setSenderDomains(new ArrayList<>(List.of(domains)));
        return a;
    }
}

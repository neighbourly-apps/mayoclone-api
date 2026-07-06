package com.mayoclone.web;

import com.mayoclone.ingest.gmail.GmailOAuthService;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * Gmail OAuth "connect a mailbox" endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/mail/gmail/connect} — AUTHENTICATED. Returns the Google
 *       consent URL (with a signed state binding the caller's account).</li>
 *   <li>{@code GET /api/mail/gmail/callback} — PUBLIC route, but the signed state
 *       is validated to recover the account; tampered/expired state is rejected.
 *       Exchanges the code, stores the encrypted refresh token, and 302-redirects
 *       back to the SPA.</li>
 * </ul>
 *
 * <p>When Gmail is not configured both endpoints return 503.
 */
@RestController
@RequestMapping("/api/mail/gmail")
public class GmailController {

    private final GmailOAuthService gmail;
    private final CurrentAccountService currentAccount;

    public GmailController(GmailOAuthService gmail, CurrentAccountService currentAccount) {
        this.gmail = gmail;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/connect")
    public ResponseEntity<Map<String, String>> connect() {
        if (!gmail.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Gmail OAuth not configured"));
        }
        String url = gmail.buildConsentUrl(currentAccount.accountId());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state) {
        if (!gmail.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String redirect = gmail.handleCallback(code, state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }
}

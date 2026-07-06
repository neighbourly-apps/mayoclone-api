package com.mayoclone.service;

import com.mayoclone.domain.Account;
import com.mayoclone.dto.AccountDto;
import com.mayoclone.dto.LoginRequest;
import com.mayoclone.dto.RegisterRequest;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Registration, login, token refresh and logout. Access tokens are minted by
 * {@link JwtService}; refresh tokens are managed (rotation + reuse detection) by
 * {@link RefreshTokenService}. Passwords are Argon2-hashed. Login errors are
 * generic to prevent user enumeration.
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final AppMetrics metrics;

    /** A throwaway hash so login timing is similar whether or not the email exists. */
    private final String dummyHash;

    public AuthService(AccountRepository accountRepo,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       AuditService auditService,
                       AppMetrics metrics) {
        this.accountRepo = accountRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-" + RANDOM.nextLong());
    }

    /** register/login result: access token + account + the raw refresh token + csrf value. */
    public record AuthResult(String accessToken, AccountDto account, String rawRefreshToken, String csrf) {
    }

    /** refresh result: rotated access + refresh tokens + a fresh csrf value. */
    public record RefreshResult(String accessToken, String rawRefreshToken, String csrf) {
    }

    @Transactional
    public AuthResult register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (accountRepo.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(CONFLICT, "Email already registered");
        }
        Account a = new Account();
        a.setBusinessName(req.businessName().trim());
        a.setEmail(email);
        a.setPasswordHash(passwordEncoder.encode(req.password()));
        a.setStationCode(blankToNull(req.stationCode()));
        a.setStationName(blankToNull(req.stationName()));
        a.setGstin(blankToNull(req.gstin()));
        a.setAddressLine(blankToNull(req.addressLine()));
        a.setPhone(blankToNull(req.phone()));
        a.setRole(Account.ROLE_OWNER);
        a.setStatus(Account.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);

        auditService.record(a.getId(), a.getEmail(), "auth.register", "account", String.valueOf(a.getId()));
        return issueFor(a);
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        Account a = accountRepo.findByEmailIgnoreCase(email).orElse(null);
        if (a == null) {
            // Burn similar CPU to a real check, then fail generically.
            passwordEncoder.matches(req.password(), dummyHash);
            loginFailed(null, email, "unknown_email");
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid email or password");
        }
        if (!passwordEncoder.matches(req.password(), a.getPasswordHash())) {
            loginFailed(a.getId(), email, "bad_password");
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid email or password");
        }
        if (!Account.STATUS_ACTIVE.equals(a.getStatus())) {
            loginFailed(a.getId(), email, "inactive");
            throw new ResponseStatusException(UNAUTHORIZED, "Account is not active");
        }
        metrics.authLogin("success");
        auditService.record(a.getId(), a.getEmail(), "auth.login", "account", String.valueOf(a.getId()));
        return issueFor(a);
    }

    private void loginFailed(Long accountId, String email, String reason) {
        metrics.authLogin("failure");
        auditService.record(accountId, email, "auth.login.failure", "account",
                accountId == null ? null : String.valueOf(accountId),
                java.util.Map.of("reason", reason));
    }

    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Missing refresh token");
        }
        RefreshTokenService.Issued rotated = refreshTokenService.rotate(rawRefreshToken);
        Account a = accountRepo.findById(rotated.accountId())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Account no longer exists"));
        String access = jwtService.issueAccessToken(a.getId(), a.getEmail(), a.getRole());
        return new RefreshResult(access, rotated.rawToken(), newCsrf());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
        auditService.record(null, null, "auth.logout", "account", null);
    }

    public AccountDto me(Long accountId) {
        Account a = accountRepo.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Account no longer exists"));
        return AccountDto.from(a);
    }

    private AuthResult issueFor(Account a) {
        String access = jwtService.issueAccessToken(a.getId(), a.getEmail(), a.getRole());
        RefreshTokenService.Issued refresh = refreshTokenService.issueNewFamily(a.getId());
        return new AuthResult(access, AccountDto.from(a), refresh.rawToken(), newCsrf());
    }

    private static String newCsrf() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}

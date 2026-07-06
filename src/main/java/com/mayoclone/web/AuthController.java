package com.mayoclone.web;

import com.mayoclone.dto.AccountDto;
import com.mayoclone.dto.AuthResponse;
import com.mayoclone.dto.LoginRequest;
import com.mayoclone.dto.RegisterRequest;
import com.mayoclone.security.AuthCookieFactory;
import com.mayoclone.security.CurrentAccountService;
import com.mayoclone.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <ul>
 *   <li>POST /register, /login → 200/201 {accessToken, account} + httpOnly refresh
 *       cookie + readable csrf cookie.</li>
 *   <li>POST /refresh → {accessToken} + rotated cookies. Guarded by the
 *       double-submit CSRF filter.</li>
 *   <li>POST /logout → revokes the refresh token + clears cookies.</li>
 *   <li>GET /me → the current account (Bearer required).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory cookies;
    private final CurrentAccountService currentAccount;

    public AuthController(AuthService authService, AuthCookieFactory cookies,
                          CurrentAccountService currentAccount) {
        this.authService = authService;
        this.cookies = cookies;
        this.currentAccount = currentAccount;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletResponse response) {
        AuthService.AuthResult result = authService.register(request);
        setAuthCookies(response, result.rawRefreshToken(), result.csrf());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(result.accessToken(), result.account()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletResponse response) {
        AuthService.AuthResult result = authService.login(request);
        setAuthCookies(response, result.rawRefreshToken(), result.csrf());
        return ResponseEntity.ok(new AuthResponse(result.accessToken(), result.account()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @CookieValue(name = AuthCookieFactory.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        setAuthCookies(response, result.rawRefreshToken(), result.csrf());
        return ResponseEntity.ok(Map.of("accessToken", result.accessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookieFactory.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clearRefreshCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clearCsrfCookie().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AccountDto me() {
        return authService.me(currentAccount.accountId());
    }

    private void setAuthCookies(HttpServletResponse response, String rawRefreshToken, String csrf) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.refreshCookie(rawRefreshToken).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.csrfCookie(csrf).toString());
    }
}

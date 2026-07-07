package com.mayoclone.web;

import com.mayoclone.dto.OtpSendRequest;
import com.mayoclone.dto.OtpSendResponse;
import com.mayoclone.dto.OtpVerifyRequest;
import com.mayoclone.service.EmailOtpService;
import com.mayoclone.service.OtpException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public email-OTP endpoints (onboarding email verification).
 *
 * <ul>
 *   <li>POST /api/auth/otp/send → {@code 200 {"sent": true}} (+ {@code devCode}
 *       only when {@code mayoclone.auth.otp-dev-mode} is true). 429 when the
 *       per-email+IP send rate limit is hit.</li>
 *   <li>POST /api/auth/otp/verify → {@code 200 {"token": "<jwt>"}} on success;
 *       {@code 400 {"error":"invalid_or_expired"}} on a bad/expired code;
 *       {@code 429 {"error":"too_many_attempts"}} once attempts are exhausted.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/otp")
public class OtpController {

    private final EmailOtpService otpService;

    public OtpController(EmailOtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send")
    public ResponseEntity<OtpSendResponse> send(@Valid @RequestBody OtpSendRequest request,
                                                HttpServletRequest http) {
        String devCode = otpService.send(request.email(), clientIp(http));
        return ResponseEntity.ok(new OtpSendResponse(true, devCode));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@Valid @RequestBody OtpVerifyRequest request) {
        String token = otpService.verify(request.email(), request.code());
        return ResponseEntity.ok(Map.of("token", token));
    }

    @ExceptionHandler(OtpException.class)
    public ResponseEntity<Map<String, String>> handleOtp(OtpException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.error()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

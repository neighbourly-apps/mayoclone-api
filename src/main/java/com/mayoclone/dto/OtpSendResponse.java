package com.mayoclone.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response for POST /api/auth/otp/send. Always {@code {"sent": true}}. The
 * {@code devCode} is included ONLY when {@code mayoclone.auth.otp-dev-mode} is
 * true (so the local UI can display it without SMTP); it is omitted otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OtpSendResponse(
        boolean sent,
        String devCode
) {
}

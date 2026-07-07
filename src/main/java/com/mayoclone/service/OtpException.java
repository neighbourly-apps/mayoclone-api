package com.mayoclone.service;

import org.springframework.http.HttpStatus;

/**
 * Signals an OTP send/verify failure that maps to a specific HTTP status and a
 * short machine-readable {@code error} code (rendered as {@code {"error": "..."}}
 * by {@code OtpController}). Kept separate from {@code ResponseStatusException} so
 * the response body shape is exactly the documented contract.
 */
public class OtpException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public OtpException(HttpStatus status, String error) {
        super(error);
        this.status = status;
        this.error = error;
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}

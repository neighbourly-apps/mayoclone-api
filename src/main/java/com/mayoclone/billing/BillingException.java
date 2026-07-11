package com.mayoclone.billing;

import org.springframework.http.HttpStatus;

/**
 * A billing error carrying a stable machine-readable {@code error} code. Rendered
 * by {@link BillingExceptionHandler} as {@code {"error": "<code>"}} with the given
 * status, giving the SPA a predictable contract (e.g. {@code billing_not_configured},
 * {@code invalid_signature}).
 */
public class BillingException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public BillingException(HttpStatus status, String error) {
        super(error);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}

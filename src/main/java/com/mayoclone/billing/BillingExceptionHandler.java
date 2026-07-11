package com.mayoclone.billing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Renders {@link BillingException} as {@code {"error": "<code>"}} with its status. */
@RestControllerAdvice
public class BillingExceptionHandler {

    @ExceptionHandler(BillingException.class)
    public ResponseEntity<Map<String, String>> handle(BillingException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getError()));
    }
}

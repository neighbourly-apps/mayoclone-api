package com.mayoclone.dto;

import java.util.List;

/** Result of a bulk order operation: which ids succeeded and which failed (with a reason). */
public record BulkResult(List<Long> updated, List<Failure> failed) {

    public record Failure(Long id, String reason) {
    }
}

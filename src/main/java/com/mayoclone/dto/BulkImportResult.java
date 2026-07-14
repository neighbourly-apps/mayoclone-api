package com.mayoclone.dto;

import java.util.List;

/**
 * Outcome of a menu bulk-import. {@code created}/{@code skipped} are counts; each
 * invalid data row surfaces as a {@link RowError} (1-based row number + reason)
 * rather than aborting the whole import.
 */
public record BulkImportResult(int created, int skipped, List<RowError> errors) {

    /** A single rejected row: {@code row} is 1-based (the header is row 1). */
    public record RowError(int row, String message) {
    }
}

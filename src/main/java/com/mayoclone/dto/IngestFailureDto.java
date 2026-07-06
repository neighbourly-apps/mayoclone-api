package com.mayoclone.dto;

import com.mayoclone.domain.IngestFailure;

import java.time.Instant;

/** Safe view of a dead-lettered ingest failure for the review queue UI. */
public record IngestFailureDto(
        Long id,
        Long vendorId,
        String fromAddress,
        String subject,
        String reason,
        String rawSnippet,
        String sourceType,
        String messageId,
        Instant createdAt
) {

    public static IngestFailureDto from(IngestFailure f) {
        return new IngestFailureDto(
                f.getId(),
                f.getVendorId(),
                f.getFromAddress(),
                f.getSubject(),
                f.getReason() == null ? null : f.getReason().name(),
                f.getRawSnippet(),
                f.getSourceType() == null ? null : f.getSourceType().name(),
                f.getMessageId(),
                f.getCreatedAt()
        );
    }
}

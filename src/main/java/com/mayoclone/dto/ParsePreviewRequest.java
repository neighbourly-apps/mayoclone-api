package com.mayoclone.dto;

/**
 * Request for {@code POST /api/dev/parse-preview}. Provide either the discrete
 * {@code from/subject/body} fields, or a full {@code raw} RFC822 email string which
 * is run through the MIME utility first to derive them. All fields are optional.
 */
public record ParsePreviewRequest(
        String from,
        String subject,
        String body,
        String raw
) {
}

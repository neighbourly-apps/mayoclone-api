package com.mayoclone.web;

import com.mayoclone.dto.ParsePreviewRequest;
import com.mayoclone.dto.ParsePreviewResponse;
import com.mayoclone.ingest.MimeEmailParser;
import com.mayoclone.ingest.ParsePreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Operator tuning tool: {@code POST /api/dev/parse-preview}.
 *
 * <p><b>AUTHENTICATED</b> (not under {@code /api/inbound/**} or {@code /api/demo/**},
 * so SecurityConfig requires a valid Bearer JWT). It runs the SAME routing + parse
 * the ingestion pipeline uses but <b>never writes to the DB</b> — safe to fire at
 * real emails while tuning parsers.
 *
 * <p>Request JSON: {@code { "from"?, "subject"?, "body"?, "raw"? }}. When {@code raw}
 * (a full RFC822 email) is present it is run through {@link MimeEmailParser} first to
 * derive from/subject/body. Response: {@link ParsePreviewResponse}
 * ({@code matchedAggregator, parsed, wouldPersist, warnings}).
 */
@RestController
@RequestMapping("/api/dev")
public class DevParsePreviewController {

    private final ParsePreviewService previewService;

    public DevParsePreviewController(ParsePreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/parse-preview")
    public ResponseEntity<ParsePreviewResponse> preview(@RequestBody ParsePreviewRequest req) {
        if (req == null) {
            throw new ResponseStatusException(BAD_REQUEST, "request body required");
        }
        try {
            return ResponseEntity.ok(previewService.preview(req));
        } catch (MimeEmailParser.MimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, "could not parse 'raw' email: " + e.getMessage());
        }
    }
}

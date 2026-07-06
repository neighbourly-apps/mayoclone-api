package com.mayoclone.web;

import com.mayoclone.dto.IngestResult;
import com.mayoclone.service.ImapIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final ImapIngestionService ingestionService;

    public SyncController(ImapIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Sync all active mailboxes now. */
    @PostMapping
    public IngestResult syncAll() {
        return ingestionService.syncAllActive();
    }
}

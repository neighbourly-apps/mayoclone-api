package com.mayoclone.web;

import com.mayoclone.dto.IngestResult;
import com.mayoclone.security.CurrentAccountService;
import com.mayoclone.service.ImapIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final ImapIngestionService ingestionService;
    private final CurrentAccountService currentAccount;

    public SyncController(ImapIngestionService ingestionService, CurrentAccountService currentAccount) {
        this.ingestionService = ingestionService;
        this.currentAccount = currentAccount;
    }

    /** Sync the caller tenant's active mailboxes now. */
    @PostMapping
    public IngestResult syncAll() {
        return ingestionService.syncAccountVendors(currentAccount.accountId());
    }
}

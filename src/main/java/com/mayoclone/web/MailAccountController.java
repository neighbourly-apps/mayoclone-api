package com.mayoclone.web;

import com.mayoclone.dto.CreateMailAccountRequest;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.MailAccountDto;
import com.mayoclone.service.MailAccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mail-accounts")
public class MailAccountController {

    private final MailAccountService accountService;

    public MailAccountController(MailAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<MailAccountDto> list() {
        return accountService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailAccountDto create(@Valid @RequestBody CreateMailAccountRequest request) {
        return accountService.create(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Trigger an on-demand IMAP sync for a single account. */
    @PostMapping("/{id}/sync")
    public IngestResult sync(@PathVariable Long id) {
        return accountService.sync(id);
    }
}

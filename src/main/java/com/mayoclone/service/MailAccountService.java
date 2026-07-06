package com.mayoclone.service;

import com.mayoclone.domain.MailAccount;
import com.mayoclone.dto.CreateMailAccountRequest;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.MailAccountDto;
import com.mayoclone.repository.MailAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** CRUD + per-account sync for restaurant mailboxes. */
@Service
public class MailAccountService {

    private final MailAccountRepository accountRepo;
    private final ImapIngestionService ingestionService;

    public MailAccountService(MailAccountRepository accountRepo,
                              ImapIngestionService ingestionService) {
        this.accountRepo = accountRepo;
        this.ingestionService = ingestionService;
    }

    public List<MailAccountDto> list() {
        return accountRepo.findAll().stream().map(MailAccountDto::from).toList();
    }

    public MailAccountDto create(CreateMailAccountRequest req) {
        MailAccount a = new MailAccount();
        a.setLabel(req.label());
        a.setImapHost(req.imapHost());
        a.setImapPort(req.imapPort());
        a.setUsername(req.username());
        a.setPassword(req.password());
        a.setUseSsl(req.useSsl() == null || req.useSsl()); // default true
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        return MailAccountDto.from(accountRepo.save(a));
    }

    public void delete(Long id) {
        if (!accountRepo.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Mail account " + id + " not found");
        }
        accountRepo.deleteById(id);
    }

    public IngestResult sync(Long id) {
        MailAccount account = accountRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Mail account " + id + " not found"));
        return ingestionService.syncAccount(account);
    }
}

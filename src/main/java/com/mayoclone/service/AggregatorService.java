package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.dto.AggregatorDto;
import com.mayoclone.dto.CreateAggregatorRequest;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.security.AccountPrincipal;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** CRUD for the managed aggregator table plus sender-domain routing. */
@Service
public class AggregatorService {

    private final AggregatorRepository repo;
    private final AuditService auditService;
    private final CurrentAccountService currentAccount;

    public AggregatorService(AggregatorRepository repo, AuditService auditService,
                             CurrentAccountService currentAccount) {
        this.repo = repo;
        this.auditService = auditService;
        this.currentAccount = currentAccount;
    }

    public List<AggregatorDto> list() {
        return repo.findAll().stream().map(AggregatorDto::from).toList();
    }

    public AggregatorDto create(CreateAggregatorRequest req) {
        if (repo.existsByCodeIgnoreCase(req.code())) {
            throw new ResponseStatusException(CONFLICT, "Aggregator code '" + req.code() + "' already exists");
        }
        Aggregator a = new Aggregator();
        a.setCode(req.code());
        a.setCreatedAt(Instant.now());
        apply(a, req);
        Aggregator saved = repo.save(a);
        audit("aggregator.create", saved);
        return AggregatorDto.from(saved);
    }

    public AggregatorDto update(Long id, CreateAggregatorRequest req) {
        Aggregator a = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Aggregator " + id + " not found"));
        if (!a.getCode().equalsIgnoreCase(req.code()) && repo.existsByCodeIgnoreCase(req.code())) {
            throw new ResponseStatusException(CONFLICT, "Aggregator code '" + req.code() + "' already exists");
        }
        a.setCode(req.code());
        apply(a, req);
        Aggregator saved = repo.save(a);
        audit("aggregator.update", saved);
        return AggregatorDto.from(saved);
    }

    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Aggregator " + id + " not found");
        }
        repo.deleteById(id);
        auditService.record(actorAccountId(), actorEmail(), "aggregator.delete", "aggregator",
                String.valueOf(id));
    }

    private void audit(String action, Aggregator a) {
        auditService.record(actorAccountId(), actorEmail(), action, "aggregator",
                String.valueOf(a.getId()),
                java.util.Map.of("code", a.getCode() == null ? "" : a.getCode()));
    }

    private Long actorAccountId() {
        AccountPrincipal p = safePrincipal();
        return p == null ? null : p.id();
    }

    private String actorEmail() {
        AccountPrincipal p = safePrincipal();
        return p == null ? null : p.email();
    }

    private AccountPrincipal safePrincipal() {
        try {
            return currentAccount.require();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Route a scraped email to an aggregator by matching the sender address
     * against each active aggregator's sender domains (case-insensitive).
     */
    public Optional<Aggregator> findBySender(String from) {
        if (from == null || from.isBlank()) {
            return Optional.empty();
        }
        String haystack = from.toLowerCase();
        return repo.findByActiveTrue().stream()
                .filter(agg -> agg.getSenderDomains().stream()
                        .filter(d -> d != null && !d.isBlank())
                        .anyMatch(d -> haystack.contains(d.toLowerCase())))
                .findFirst();
    }

    private void apply(Aggregator a, CreateAggregatorRequest req) {
        a.setName(req.name());
        a.setSenderDomains(req.senderDomains() != null ? new ArrayList<>(req.senderDomains()) : new ArrayList<>());
        a.setSubjectHint(req.subjectHint());
        a.setBrandColor(req.brandColor());
        a.setWebsiteUrl(req.websiteUrl());
        a.setActive(req.active() == null || req.active()); // default true
    }
}

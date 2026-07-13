package com.mayoclone.service;

import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.dto.IngestFailureDto;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.RawMessage;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant-scoped review queue over {@code ingest_failure}. List/retry/delete all
 * operate ONLY on the caller's account (cross-tenant ids surface as 404). Retry
 * re-runs the stored message through {@link IngestionCore}; on success the
 * failure row is marked resolved (audit trail) instead of being deleted.
 */
@Service
public class IngestFailureService {

    private final IngestFailureRepository repo;
    private final IngestionCore ingestionCore;
    private final CurrentAccountService currentAccount;

    public IngestFailureService(IngestFailureRepository repo,
                                IngestionCore ingestionCore,
                                CurrentAccountService currentAccount) {
        this.repo = repo;
        this.ingestionCore = ingestionCore;
        this.currentAccount = currentAccount;
    }

    public List<IngestFailureDto> list() {
        return repo.findByAccountIdOrderByCreatedAtDesc(currentAccount.accountId()).stream()
                .map(IngestFailureDto::from).toList();
    }

    /**
     * Re-run a stored dead-lettered message through the pipeline. Reconstructs the
     * {@link RawMessage} from the saved from/subject/rawBody/messageId — using the
     * FULL {@code rawBody} (not the truncated snippet) so the replay is faithful.
     * This is how emails that failed before a new aggregator/parser existed get
     * re-ingested.
     *
     * <p>On success the original row is marked {@code resolvedAt} (audit trail) rather
     * than deleted. If it still can't be ingested, the retry's freshly written
     * duplicate dead-letter is dropped and the original stays in the queue (unresolved).
     */
    @Transactional
    public IngestResult retry(Long id) {
        Long accountId = currentAccount.accountId();
        IngestFailure f = repo.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ingest failure " + id + " not found"));

        MailSourceType sourceType = f.getSourceType() == null ? MailSourceType.IMAP : f.getSourceType();
        String body = f.getRawBody() != null ? f.getRawBody() : f.getRawSnippet();
        RawMessage msg = new RawMessage(f.getFromAddress(), f.getSubject(), body, f.getMessageId());

        // Snapshot the existing failure ids so we can tell whether this retry wrote a
        // fresh dead-letter (still un-ingestable) or ingested cleanly (no new row).
        Set<Long> before = repo.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(IngestFailure::getId).collect(Collectors.toSet());

        IngestResult result = ingestionCore.process(accountId, f.getVendorId(), sourceType, msg);

        List<IngestFailure> fresh = repo.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .filter(x -> !before.contains(x.getId())).toList();

        if (fresh.isEmpty()) {
            // Ingested (or already present) — no new dead-letter. Mark resolved.
            f.setResolvedAt(Instant.now());
            repo.save(f);
        } else {
            // Still un-ingestable: drop the duplicate the retry just wrote, keep the
            // original visible/unresolved in the review queue.
            fresh.forEach(repo::delete);
        }
        return result;
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsByIdAndAccountId(id, currentAccount.accountId())) {
            throw new ResponseStatusException(NOT_FOUND, "Ingest failure " + id + " not found");
        }
        repo.deleteById(id);
    }
}

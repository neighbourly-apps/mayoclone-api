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

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant-scoped review queue over {@code ingest_failure}. List/retry/delete all
 * operate ONLY on the caller's account (cross-tenant ids surface as 404). Retry
 * re-runs the stored message through {@link IngestionCore}; on success the
 * failure row is removed.
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

    /** Re-run a failed message through the pipeline; delete the row if it now succeeds. */
    @Transactional
    public IngestResult retry(Long id) {
        Long accountId = currentAccount.accountId();
        IngestFailure f = repo.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ingest failure " + id + " not found"));

        MailSourceType sourceType = f.getSourceType() == null ? MailSourceType.IMAP : f.getSourceType();
        RawMessage msg = new RawMessage(f.getFromAddress(), f.getSubject(), f.getRawSnippet(), f.getMessageId());
        IngestResult result = ingestionCore.process(accountId, f.getVendorId(), sourceType, msg);

        // On a clean run the original row is stale — remove it (and any duplicate
        // failure the retry may have just written for the same message).
        repo.deleteById(id);
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

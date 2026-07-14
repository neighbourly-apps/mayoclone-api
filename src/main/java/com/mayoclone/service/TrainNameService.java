package com.mayoclone.service;

import com.mayoclone.domain.TrainNameCatalog;
import com.mayoclone.repository.TrainNameCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The learned train number -> name catalog (see {@link TrainNameCatalog}).
 *
 * <p>{@link #record} teaches the catalog a name whenever an email carried one;
 * {@link #resolve} fills a blank name on an order that had only the number. This
 * makes the train name robust across aggregators without depending on the
 * (default-off) dashboard scraper.
 */
@Service
public class TrainNameService {

    private static final Logger log = LoggerFactory.getLogger(TrainNameService.class);

    private final TrainNameCatalogRepository repo;

    public TrainNameService(TrainNameCatalogRepository repo) {
        this.repo = repo;
    }

    /** The known name for a train number, if we've ever learned one. */
    @Transactional(readOnly = true)
    public Optional<String> resolve(String trainNumber) {
        if (trainNumber == null || trainNumber.isBlank()) {
            return Optional.empty();
        }
        return repo.findById(trainNumber.trim()).map(TrainNameCatalog::getTrainName);
    }

    /**
     * Remember a train's name (last-write-wins, so a stale/typo'd name self-heals).
     * No-op unless BOTH number and name are present. Runs in its OWN transaction and
     * swallows any failure (e.g. a concurrent-insert race) so learning a name can
     * never fail — or roll back — the order ingestion that triggered it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String trainNumber, String trainName) {
        if (trainNumber == null || trainNumber.isBlank()
                || trainName == null || trainName.isBlank()) {
            return;
        }
        String num = trainNumber.trim();
        String name = trainName.trim();
        try {
            TrainNameCatalog existing = repo.findById(num).orElse(null);
            if (existing == null) {
                repo.save(new TrainNameCatalog(num, name));
            } else if (!name.equals(existing.getTrainName())) {
                existing.setTrainName(name);
                existing.setUpdatedAt(Instant.now());
                repo.save(existing);
            }
        } catch (RuntimeException e) {
            // Best-effort: a rare race (two orders for the same new train at once) is fine.
            log.debug("train-name catalog upsert skipped for {}: {}", num, e.toString());
        }
    }
}

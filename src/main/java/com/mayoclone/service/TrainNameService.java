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
     * Remember a train's name. No-op unless the number is present and the name looks like a
     * real train name — this guard is critical because the catalog is GLOBAL (shared across
     * all tenants): without it, one vendor's garbled capture ("No", "EXP", a marketing token)
     * would overwrite the correct/seeded name for that train number for EVERYONE. A plausible
     * name still updates (last-write-wins) so the operator's own vocabulary refines the seed.
     * Runs in its OWN transaction and swallows any failure so learning can never roll back the
     * order ingestion that triggered it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String trainNumber, String trainName) {
        if (trainNumber == null || trainNumber.isBlank() || !looksLikeTrainName(trainName)) {
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

    /**
     * A plausible train name: ≥4 chars, ≥3 letters, and not a lone label/suffix token that a
     * regex sometimes mis-captures ("No", "Name", "Train", "Exp", "SF", "Express", "Special").
     */
    static boolean looksLikeTrainName(String name) {
        if (name == null) {
            return false;
        }
        String t = name.trim();
        if (t.length() < 4) {
            return false;
        }
        if (t.chars().filter(Character::isLetter).count() < 3) {
            return false;
        }
        return !t.matches("(?i)no|name|train|exp|sf|express|special|sup|superfast");
    }
}

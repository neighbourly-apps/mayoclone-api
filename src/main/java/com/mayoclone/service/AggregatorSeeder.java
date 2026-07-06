package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.repository.AggregatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Seeds the {@code aggregator} table on startup with the real IRCTC e-catering
 * partners — but only when the table is empty, so operator edits made via the
 * API are never overwritten.
 *
 * <p>NOTE: the sender domains below are best-effort defaults and are fully
 * editable via {@code /api/aggregators} at runtime.
 */
@Component
public class AggregatorSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AggregatorSeeder.class);

    private final AggregatorRepository repo;

    public AggregatorSeeder(AggregatorRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return; // already seeded / operator-managed — leave as-is
        }
        List<Aggregator> seeds = List.of(
                seed("ZOOP", "Zoop", List.of("zoopindia.com"), "#E4002B", "https://www.zoopindia.com"),
                seed("RAILRESTRO", "RailRestro", List.of("railrestro.com"), "#C8102E", "https://www.railrestro.com"),
                seed("GOFOODIE", "Gofoodie", List.of("gofoodieonline.com"), "#F7941D", "https://www.gofoodieonline.com"),
                seed("COMESUM", "Comesum", List.of("comesum.com"), "#00A651", "https://www.comesum.com"),
                seed("TRAVELKHANA", "TravelKhana", List.of("travelkhana.com"), "#ED1C24", "https://www.travelkhana.com"),
                seed("IRCTC_ECATERING", "IRCTC eCatering",
                        List.of("ecatering.irctc.co.in", "irctc.co.in"), "#213A8F", "https://www.ecatering.irctc.co.in")
        );
        repo.saveAll(seeds);
        log.info("Seeded {} IRCTC e-catering aggregators", seeds.size());
    }

    private Aggregator seed(String code, String name, List<String> domains, String brandColor, String website) {
        Aggregator a = new Aggregator();
        a.setCode(code);
        a.setName(name);
        a.setSenderDomains(new java.util.ArrayList<>(domains));
        a.setBrandColor(brandColor);
        a.setWebsiteUrl(website);
        a.setActive(true);
        a.setCommissionRate(new java.math.BigDecimal("0.15")); // default 15% commission
        a.setCreatedAt(Instant.now());
        return a;
    }
}

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
 * partners. Additive: any seed whose {@code code} is not already present is
 * inserted; existing rows (and any operator edits to them) are left untouched,
 * so new partners roll out on upgrade without clobbering managed data.
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
        List<Aggregator> seeds = List.of(
                seed("ZOOP", "Zoop", List.of("zoopindia.com"), "#E4002B", "https://www.zoopindia.com"),
                seed("RAILRESTRO", "RailRestro", List.of("railrestro.com"), "#C8102E", "https://www.railrestro.com"),
                seed("GOFOODIE", "Gofoodie", List.of("gofoodieonline.com"), "#F7941D", "https://www.gofoodieonline.com"),
                seed("COMESUM", "Comesum", List.of("comesum.com"), "#00A651", "https://www.comesum.com"),
                seed("TRAVELKHANA", "TravelKhana", List.of("travelkhana.com"), "#ED1C24", "https://www.travelkhana.com"),
                seed("IRCTC_ECATERING", "IRCTC eCatering",
                        List.of("ecatering.irctc.co.in", "irctc.co.in"), "#213A8F", "https://www.ecatering.irctc.co.in"),
                seed("RELFOOD", "REL Food", List.of("relfood.com"), "#E8552D", "https://www.relfood.com"),
                seed("RAILRECIPE", "RailRecipe", List.of("railrecipe.com"), "#1BA94C", "https://www.railrecipe.com"),
                seed("RAJBHOGKHANA", "Rajbhog Khana", List.of("rajbhogkhana.com"), "#D4380D", "https://www.rajbhogkhana.com")
        );
        List<Aggregator> toInsert = seeds.stream()
                .filter(s -> !repo.existsByCodeIgnoreCase(s.getCode()))
                .toList();
        if (toInsert.isEmpty()) {
            return; // every seed code already present — nothing to add
        }
        repo.saveAll(toInsert);
        log.info("Seeded {} new IRCTC e-catering aggregators", toInsert.size());
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

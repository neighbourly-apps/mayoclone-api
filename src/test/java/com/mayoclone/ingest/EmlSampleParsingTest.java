package com.mayoclone.ingest;

import com.mayoclone.dto.ParsePreviewResponse;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads every {@code .eml} in {@code src/test/resources/samples/}, runs the real
 * MIME → route → parse pipeline (without persisting), and:
 * <ul>
 *   <li>logs a concise parse report for EVERY file (so a human can eyeball a real
 *       drop-in), and</li>
 *   <li>hard-asserts key fields ONLY for the built-in {@code synthetic_*.eml}
 *       samples — real drop-ins that parse imperfectly never fail CI.</li>
 * </ul>
 */
class EmlSampleParsingTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmlSampleParsingTest.class);

    @Autowired
    private MimeEmailParser mimeEmailParser;

    @Autowired
    private ParsePreviewService previewService;

    @Test
    void everySampleParsesAndSyntheticSamplesExtractKeyFields() throws Exception {
        File dir = new ClassPathResource("samples").getFile();
        assertTrue(dir.isDirectory(), "samples/ folder must exist on the test classpath");

        File[] emls = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".eml"));
        assertNotNull(emls, "could not list samples/");
        List<File> files = new ArrayList<>(Arrays.asList(emls));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        assertFalse(files.isEmpty(), "expected at least the synthetic .eml samples");

        int synthetic = 0;
        for (File f : files) {
            RawMessage raw;
            try (InputStream in = new FileInputStream(f)) {
                raw = mimeEmailParser.parse(in);
            }
            ParsePreviewResponse preview =
                    previewService.preview(raw.from(), raw.subject(), raw.body(), raw.messageId());

            log.info("\n{}", report(f.getName(), raw, preview));

            if (f.getName().startsWith("synthetic_")) {
                synthetic++;
                assertHardFields(f.getName(), preview);
            }
        }
        assertTrue(synthetic >= 3, "expected the built-in synthetic samples to be present");
    }

    /** Hard assertions for the built-in synthetic samples only. */
    private static void assertHardFields(String name, ParsePreviewResponse preview) {
        assertNotNull(preview.matchedAggregator(), name + ": expected an aggregator to match");
        assertTrue(preview.wouldPersist(), name + ": expected wouldPersist=true");
        var p = preview.parsed();
        assertNotNull(p, name + ": expected a parsed order");
        assertNotNull(p.pnr(), name + ": PNR should be extracted");
        assertNotNull(p.trainNumber(), name + ": train number should be extracted");
        assertNotNull(p.deliveryStationCode(), name + ": delivery station code should be extracted");
        assertNotNull(p.amount(), name + ": amount should be extracted");
        assertTrue(p.amount().signum() > 0, name + ": amount should be > 0");
        assertNotNull(p.items(), name + ": items list should be non-null");
        assertFalse(p.items().isEmpty(), name + ": expected at least one line item");
    }

    /** Human-eyeball parse report for one sample. */
    private static String report(String file, RawMessage raw, ParsePreviewResponse preview) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(file).append(" ===\n");
        sb.append("  from:    ").append(raw.from()).append('\n');
        sb.append("  subject: ").append(raw.subject()).append('\n');
        sb.append("  aggregator: ").append(preview.matchedAggregator() == null
                ? "(none matched)" : preview.matchedAggregator().code()
                        + " / " + preview.matchedAggregator().name()).append('\n');
        sb.append("  wouldPersist: ").append(preview.wouldPersist()).append('\n');
        var p = preview.parsed();
        if (p != null) {
            sb.append("  externalOrderId: ").append(p.externalOrderId()).append('\n');
            sb.append("  pnr: ").append(p.pnr())
                    .append("  train: ").append(p.trainNumber()).append(' ').append(p.trainName()).append('\n');
            sb.append("  coach/berth: ").append(p.coach()).append('/').append(p.berth()).append('\n');
            sb.append("  delivery: ").append(p.deliveryStationCode()).append(' ')
                    .append(p.deliveryStationName())
                    .append("  date=").append(p.deliveryDate())
                    .append("  slot=").append(p.deliverySlot()).append('\n');
            sb.append("  passenger: ").append(p.passengerName())
                    .append("  phone=").append(p.passengerPhone()).append('\n');
            sb.append("  amount: ").append(p.amount()).append(' ').append(p.currency()).append('\n');
            sb.append("  items (").append(p.items() == null ? 0 : p.items().size()).append("):\n");
            if (p.items() != null) {
                p.items().forEach(i -> sb.append("     - ").append(i.getQty()).append(" x ")
                        .append(i.getName()).append(" @ ").append(i.getPrice()).append('\n'));
            }
        }
        sb.append("  warnings: ").append(preview.warnings());
        return sb.toString();
    }
}

package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJobStatus;
import com.mayoclone.repository.IngestJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Typed Micrometer meters for the background job queue, exposed via the Prometheus
 * registry at {@code /actuator/prometheus}.
 *
 * <ul>
 *   <li>{@code mayoclone.jobs.enqueued}    — counter, tag {@code jobType}</li>
 *   <li>{@code mayoclone.jobs.processed}   — counter, tags {@code jobType}, {@code result} (done|failed|dead)</li>
 *   <li>{@code mayoclone.jobs.queue.depth} — gauge, live count of PENDING jobs</li>
 *   <li>{@code mayoclone.jobs.duration}    — timer, tag {@code jobType}</li>
 * </ul>
 */
@Component
public class JobMetrics {

    public static final String ENQUEUED = "mayoclone.jobs.enqueued";
    public static final String PROCESSED = "mayoclone.jobs.processed";
    public static final String QUEUE_DEPTH = "mayoclone.jobs.queue.depth";
    public static final String DURATION = "mayoclone.jobs.duration";

    public static final String RESULT_DONE = "done";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_DEAD = "dead";

    private final MeterRegistry registry;

    public JobMetrics(MeterRegistry registry, IngestJobRepository repository) {
        this.registry = registry;
        // Depth gauge reads the live PENDING count on scrape. Held as a strong ref
        // via the repository closure so it is not GC'd.
        Gauge.builder(QUEUE_DEPTH, repository, r -> (double) r.countByStatus(IngestJobStatus.PENDING))
                .description("Number of PENDING background jobs")
                .register(registry);
    }

    public void enqueued(String jobType) {
        Counter.builder(ENQUEUED)
                .tag("jobType", safe(jobType))
                .register(registry)
                .increment();
    }

    public void processed(String jobType, String result) {
        Counter.builder(PROCESSED)
                .tag("jobType", safe(jobType))
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    /** Record how long a handler took for a job type. */
    public void recordDuration(String jobType, long nanos) {
        Timer.builder(DURATION)
                .tag("jobType", safe(jobType))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    private static String safe(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }
}

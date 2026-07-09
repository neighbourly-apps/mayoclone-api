package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the {@link JobQueue}, dispatches claimed jobs to their {@link JobHandler}s on
 * a bounded worker pool, and drives each to {@code complete}/{@code fail}. Everything
 * is config-gated by {@code mayoclone.jobs.*}; in the TEST profile
 * {@code mayoclone.jobs.enabled=false} so the scheduler stays out of the way and
 * tests call {@link #process} directly.
 *
 * <p>Handlers are discovered as Spring beans: every {@link JobHandler} in the context
 * is registered under its {@link JobHandler#jobType()}. A job whose type has no
 * handler is dead-lettered with a clear error (the queue never blocks on it).
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final Map<String, JobHandler> handlers;
    private final JobMetrics metrics;

    private final boolean enabled;
    private final int batchSize;
    private final String workerId;

    private final ThreadPoolExecutor executor;

    public JobWorker(JobQueue queue,
                     List<JobHandler> handlerBeans,
                     JobMetrics metrics,
                     @Value("${mayoclone.jobs.enabled:true}") boolean enabled,
                     @Value("${mayoclone.jobs.batch-size:10}") int batchSize,
                     @Value("${mayoclone.jobs.concurrency:8}") int concurrency) {
        this.queue = queue;
        this.metrics = metrics;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.handlers = handlerBeans.stream()
                .collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.workerId = instanceId();

        int threads = Math.max(1, concurrency);
        // Bounded queue + CallerRuns backpressure: if the pool is saturated the
        // dispatcher thread runs the job itself, naturally throttling claiming.
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(threads, batchSize) * 2),
                new NamedThreadFactory("job-worker"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.allowCoreThreadTimeOut(true);

        if (!handlers.isEmpty()) {
            log.info("JobWorker registered {} handler(s): {}", handlers.size(), handlers.keySet());
        }
    }

    // ---------------------------------------------------------------- schedule

    /**
     * Claim a batch and submit each job to the pool. Blocks briefly on the CountDownLatch
     * so the scheduled invocation does not overlap its own next run while a batch is
     * mid-flight (fixedDelay already prevents overlap, but this bounds a slow batch).
     */
    @Scheduled(fixedDelayString = "${mayoclone.jobs.poll-ms:2000}")
    public void dispatch() {
        if (!enabled) {
            return;
        }
        try {
            List<IngestJob> claimed = queue.claim(workerId, batchSize);
            if (claimed.isEmpty()) {
                return;
            }
            CountDownLatch done = new CountDownLatch(claimed.size());
            for (IngestJob job : claimed) {
                executor.execute(() -> {
                    try {
                        process(job);
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Job dispatch cycle failed: {}", e.toString());
        }
    }

    /** Crash-recovery sweep — independent of the dispatch cadence. */
    @Scheduled(fixedDelayString = "${mayoclone.jobs.reap-ms:60000}")
    public void reap() {
        if (!enabled) {
            return;
        }
        try {
            queue.reapStuck();
        } catch (RuntimeException e) {
            log.warn("reapStuck failed: {}", e.toString());
        }
    }

    // ----------------------------------------------------------------- process

    /**
     * Execute a single claimed job: route to its handler, then {@code complete} on
     * success or {@code fail} on error. A missing handler is a permanent failure and
     * dead-letters immediately. Public so tests can drive the handle path directly
     * (with the scheduler disabled).
     */
    public void process(IngestJob job) {
        JobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            String msg = "No JobHandler registered for jobType=" + job.getJobType();
            log.warn("{} (job id={})", msg, job.getId());
            queue.fail(job.getId(), msg, true);
            return;
        }
        long start = System.nanoTime();
        try {
            handler.handle(job);
            queue.complete(job.getId());
        } catch (Exception e) {
            queue.fail(job.getId(), e.toString());
            log.warn("Job handler failed: id={} type={} error={}", job.getId(), job.getJobType(), e.toString());
        } finally {
            metrics.recordDuration(job.getJobType(), System.nanoTime() - start);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** For assertions/introspection. */
    public java.util.Set<String> registeredJobTypes() {
        return handlers.keySet();
    }

    private static String instanceId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + Long.toHexString(ProcessHandle.current().pid());
    }

    /** Minimal named, daemon-less thread factory so shutdown can drain in-flight work. */
    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(false);
            return t;
        }
    }
}

package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;

/**
 * Strategy that knows how to execute one kind of {@link IngestJob}. Handlers are
 * plain Spring beans: declare a {@code @Component} (or {@code @Bean}) implementing
 * this interface and the {@link JobWorker} auto-discovers it by {@link #jobType()}.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #jobType()} must equal the {@code job_type} stored on the jobs it
 *       handles (e.g. {@code "GMAIL_HISTORY"}). It must be unique across beans.</li>
 *   <li>{@link #handle} runs OUTSIDE the claim transaction on a worker thread. It
 *       should be idempotent (delivery is at-least-once) and may throw to signal a
 *       transient failure — the worker will apply exponential backoff and retry
 *       until {@code max_attempts}, then dead-letter.</li>
 * </ul>
 */
public interface JobHandler {

    /** The {@code job_type} this handler processes. Must be unique across beans. */
    String jobType();

    /** Execute the job. Throw to trigger retry/backoff; return normally to complete. */
    void handle(IngestJob job) throws Exception;
}

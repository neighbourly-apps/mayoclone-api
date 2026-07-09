package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A no-op test {@link JobHandler} discovered as a Spring bean. Counts how many jobs
 * it processed so tests can assert the dispatch → handle → complete path. Lives on
 * the TEST classpath only.
 */
@Component
public class RecordingTestHandler implements JobHandler {

    public static final String JOB_TYPE = "TEST_RECORDING";

    private final AtomicInteger count = new AtomicInteger();

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(IngestJob job) {
        count.incrementAndGet();
    }

    public int count() {
        return count.get();
    }

    public void reset() {
        count.set(0);
    }
}

package com.mayoclone.jobs;

import com.mayoclone.domain.IngestJob;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A test {@link JobHandler} that throws on demand, so tests can exercise the
 * fail → backoff → retry path. TEST classpath only.
 */
@Component
public class FailingTestHandler implements JobHandler {

    public static final String JOB_TYPE = "TEST_FAILING";

    /** Toggled per-test; when true {@link #handle} throws. */
    public static final AtomicBoolean SHOULD_THROW = new AtomicBoolean(true);

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(IngestJob job) {
        if (SHOULD_THROW.get()) {
            throw new IllegalStateException("intentional test failure");
        }
    }
}

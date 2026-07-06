package com.mayoclone.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Central, typed access to the application's Micrometer meters so metric names
 * and tag keys live in exactly one place. Exposed via the Prometheus registry at
 * {@code /actuator/prometheus}.
 *
 * <ul>
 *   <li>{@code mayoclone.orders.ingested}   — tags: aggregator, sourceType</li>
 *   <li>{@code mayoclone.ingest.failures}   — tag: reason</li>
 *   <li>{@code mayoclone.webhook.inbound}   — tag: result</li>
 *   <li>{@code mayoclone.auth.login}        — tag: result</li>
 *   <li>{@code mayoclone.sync.duration}     — timer around the sync path</li>
 * </ul>
 */
@Component
public class AppMetrics {

    public static final String ORDERS_INGESTED = "mayoclone.orders.ingested";
    public static final String INGEST_FAILURES = "mayoclone.ingest.failures";
    public static final String WEBHOOK_INBOUND = "mayoclone.webhook.inbound";
    public static final String AUTH_LOGIN = "mayoclone.auth.login";
    public static final String SYNC_DURATION = "mayoclone.sync.duration";

    private final MeterRegistry registry;

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderIngested(String aggregator, String sourceType) {
        Counter.builder(ORDERS_INGESTED)
                .tag("aggregator", safe(aggregator))
                .tag("sourceType", safe(sourceType))
                .register(registry)
                .increment();
    }

    public void ingestFailure(String reason) {
        Counter.builder(INGEST_FAILURES)
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    public void webhookInbound(String result) {
        Counter.builder(WEBHOOK_INBOUND)
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    public void authLogin(String result) {
        Counter.builder(AUTH_LOGIN)
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    /** Time an ingestion/sync operation. */
    public Timer.Sample startSyncTimer() {
        return Timer.start(registry);
    }

    public void stopSyncTimer(Timer.Sample sample, String sourceType) {
        sample.stop(Timer.builder(SYNC_DURATION)
                .tag("sourceType", safe(sourceType))
                .register(registry));
    }

    private static String safe(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }
}

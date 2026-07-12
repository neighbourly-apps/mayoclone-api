package com.mayoclone.util;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Factory for {@link RestClient}s that always carry connect + read timeouts, so a
 * half-open socket to an external service (Razorpay, Google/Gmail) can never hang a
 * servlet or worker thread forever. Mirrors the timeout pattern in
 * {@code NotificationDispatcher}. Bare {@code RestClient.create()} has NO timeouts.
 *
 * <p>Defaults: {@value #CONNECT_SECONDS}s to open the socket, {@value #READ_SECONDS}s
 * waiting on a response.
 */
public final class TimeoutRestClients {

    /** Time to establish the TCP/TLS connection before giving up. */
    public static final int CONNECT_SECONDS = 3;
    /** Time to wait on a response read before giving up. */
    public static final int READ_SECONDS = 10;

    private TimeoutRestClients() {
    }

    /** A {@link ClientHttpRequestFactory} with the given connect/read timeouts. */
    public static ClientHttpRequestFactory factory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) connect.toMillis());
        f.setReadTimeout((int) read.toMillis());
        return f;
    }

    /** A {@link RestClient} with the default (3s connect / 10s read) timeouts. */
    public static RestClient create() {
        return RestClient.builder()
                .requestFactory(factory(Duration.ofSeconds(CONNECT_SECONDS), Duration.ofSeconds(READ_SECONDS)))
                .build();
    }
}

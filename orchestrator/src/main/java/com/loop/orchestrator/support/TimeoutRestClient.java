package com.loop.orchestrator.support;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds the only kind of HTTP client this system is allowed to have: one that gives up.
 *
 * <p>Every outbound client here was created with {@code RestClient.builder()} and no request
 * factory, which means no connect timeout and no read timeout at all. That looks covered by
 * Temporal and is not. Temporal times out the <b>activity</b>, but it cannot interrupt a
 * thread blocked in a socket read: the activity is abandoned while the thread stays parked
 * forever. A proxy that accepts connections and never answers therefore leaks one activity
 * thread per call until the worker's pool is exhausted and it silently stops taking work,
 * with no error anywhere to explain it.
 *
 * <p>Read timeouts must stay comfortably below the activity's start-to-close timeout, so the
 * call fails and the activity retries rather than the activity dying around a live call.
 */
public final class TimeoutRestClient {

    private TimeoutRestClient() {
    }

    public static RestClient create(String baseUrl, Duration connect, Duration read) {
        return builder(baseUrl, connect, read).build();
    }

    /** As above, with a bearer token — the LiteLLM proxy key, never a provider credential. */
    public static RestClient createWithBearer(String baseUrl, String token,
                                             Duration connect, Duration read) {
        return builder(baseUrl, connect, read)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    private static RestClient.Builder builder(String baseUrl, Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
    }
}

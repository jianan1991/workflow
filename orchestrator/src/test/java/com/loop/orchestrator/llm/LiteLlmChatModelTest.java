package com.loop.orchestrator.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transport, not the agents.
 *
 * <p>These clients were built with no timeouts of any kind, which looked covered by
 * Temporal and was not: Temporal times out the activity, but cannot interrupt a thread
 * parked in a socket read. The activity is abandoned while the thread stays blocked, and a
 * proxy that accepts connections without answering leaks one worker thread per call until
 * the pool is empty and the worker quietly stops taking work.
 */
class LiteLlmChatModelTest {

    private static final Map<String, String> TIERS = Map.of("pm", "strong");

    @Test
    @DisplayName("a proxy that accepts the connection and never answers fails fast, instead of parking the thread")
    void hangingProxyFailsFast() throws IOException {
        // A real socket that accepts and then says nothing at all. Nothing is sent back,
        // so only a read timeout can end this call.
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try {
                    silent.accept();
                    Thread.sleep(Duration.ofSeconds(30).toMillis());
                } catch (Exception ignored) {
                    // The test is done with it either way.
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            LiteLlmChatModel model = new LiteLlmChatModel(
                    "http://127.0.0.1:" + silent.getLocalPort(), "proxy-key", TIERS,
                    Duration.ofSeconds(2), Duration.ofSeconds(1));

            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> model.complete(
                    new ChatModel.ChatRequest("pm", "system", "user", 64)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("got no reply")
                    .as("no reply at all and a 429 send a person to different places, so "
                            + "they must not read the same")
                    .hasMessageContaining("within 1s");

            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(took)
                    .as("it gave up on its own rather than waiting for something external "
                            + "to notice, which is what nothing was doing before")
                    .isLessThan(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("an agent with no tier is refused before any call is made")
    void unmappedAgentIsRefused() {
        LiteLlmChatModel model = new LiteLlmChatModel(
                "http://127.0.0.1:1", "proxy-key", TIERS,
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> model.complete(
                new ChatModel.ChatRequest("designer", "system", "user", 64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no tier");
    }
}

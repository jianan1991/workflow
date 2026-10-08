package com.loop.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import com.loop.orchestrator.support.TimeoutRestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The real path: an OpenAI-compatible call to the LiteLLM proxy.
 *
 * <p>The request names a <b>tier</b> — {@code strong}, {@code mid}, {@code cheap},
 * {@code review} — never a provider model id. The proxy resolves it. That keeps model
 * choice in one file, keeps the provider credential out of this process, and means
 * swapping the cheap tier for a local model later never touches Java.
 */
public class LiteLlmChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmChatModel.class);

    /** A proxy that has not accepted the connection by now is not going to. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Long enough for a 400k-token call to a strong model, short enough to stay well under
     * the activity's start-to-close timeout (10 minutes for planning, 30 for build) so a
     * stalled call fails and retries rather than parking a worker thread for good.
     */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    private final RestClient http;
    private final Map<String, String> agentToTier;
    private final String baseUrl;
    private final Duration readTimeout;

    public LiteLlmChatModel(String baseUrl, String apiKey, Map<String, String> agentToTier) {
        this(baseUrl, apiKey, agentToTier, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** Timeouts given explicitly, so a test can prove a hanging proxy fails fast. */
    public LiteLlmChatModel(String baseUrl, String apiKey, Map<String, String> agentToTier,
                            Duration connectTimeout, Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.agentToTier = Map.copyOf(agentToTier);
        this.readTimeout = readTimeout;
        this.http = TimeoutRestClient.createWithBearer(
                baseUrl, apiKey, connectTimeout, readTimeout);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        String tier = agentToTier.get(request.agent());
        if (tier == null) {
            throw new IllegalStateException(("agent '%s' has no tier — add agent.%s.tier to "
                    + "cost-model.properties, or it will never be priced either")
                    .formatted(request.agent(), request.agent()));
        }

        JsonNode response;
        try {
            response = http.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", tier,
                            "max_tokens", request.maxOutputTokens(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", request.system()),
                                    Map.of("role", "user", "content", request.user()))))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(("model call for '%s' (tier %s) failed with %d via "
                    + "%s: %s").formatted(request.agent(), tier, e.getStatusCode().value(),
                    baseUrl, e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            // Distinct from an HTTP error on purpose. "No reply at all" and "the proxy said
            // 429" lead a person to different places, and this message is what ends up in
            // the blocked story's note.
            throw new IllegalStateException(("model call for '%s' (tier %s) got no reply from "
                    + "%s within %ds: %s").formatted(request.agent(), tier, baseUrl,
                    readTimeout.toSeconds(), e.getMessage()), e);
        }

        if (response == null || response.path("choices").isEmpty()) {
            throw new IllegalStateException(
                    "no choices came back for agent '" + request.agent() + "'");
        }

        String text = response.path("choices").get(0).path("message").path("content").asText("");
        JsonNode usage = response.path("usage");

        // Usage is what eventually replaces the guesses in cost-model.properties. If the
        // proxy stops reporting it, the "actual" column silently becomes zero rather than
        // wrong, so say so loudly instead.
        if (usage.isMissingNode()) {
            log.warn("[{}] no usage reported by the proxy — cost accounting will under-report",
                    request.agent());
        }

        ChatResponse result = new ChatResponse(
                text,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                response.path("model").asText(tier));

        log.info("[{}] tier {} -> {} — {} in, {} out",
                request.agent(), tier, result.model(), result.inputTokens(), result.outputTokens());
        return result;
    }

    @Override
    public String describe() {
        return "litellm at " + baseUrl + " (" + agentToTier.size() + " agents mapped to tiers)";
    }
}

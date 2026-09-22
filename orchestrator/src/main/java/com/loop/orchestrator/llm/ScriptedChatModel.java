package com.loop.orchestrator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A model that answers from a script. No key, no network, no cost.
 *
 * <p>Two rules keep it honest.
 *
 * <p><b>It reports plausible token counts,</b> derived from the actual prompt and reply
 * rather than returned as zero. Cost accounting is exercised on every run, so a bug in the
 * arithmetic surfaces here instead of on the first real invoice.
 *
 * <p><b>It can be told to misbehave.</b> A model that always returns clean JSON tests only
 * the happy path, and the failure an agent will actually hit in production is a reply that
 * is almost right. {@link #failing} scripts that case so the parsing and error handling
 * above it are exercised too.
 */
public class ScriptedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ScriptedChatModel.class);

    /**
     * Rough enough to be useful and wrong enough not to be trusted. Real tokenisation is
     * model-specific; this is here so the cost path has non-zero numbers flowing through
     * it, not so anyone can forecast from it.
     */
    private static final double CHARS_PER_TOKEN = 4.0;

    private final Map<String, Function<ChatRequest, String>> script;
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedChatModel(Map<String, Function<ChatRequest, String>> script) {
        this.script = new LinkedHashMap<>(script);
    }

    /** Fixed replies, keyed by agent. */
    public static ScriptedChatModel of(Map<String, String> replies) {
        Map<String, Function<ChatRequest, String>> script = new LinkedHashMap<>();
        replies.forEach((agent, reply) -> script.put(agent, request -> reply));
        return new ScriptedChatModel(script);
    }

    /** A model that returns something unusable, for testing the error path above it. */
    public static ScriptedChatModel failing(String agent, String malformedReply) {
        return new ScriptedChatModel(Map.of(agent, request -> malformedReply));
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Function<ChatRequest, String> scripted = script.get(request.agent());
        if (scripted == null) {
            // Silence here would mean an agent quietly receiving an empty answer and
            // carrying on, which is the single worst failure mode in this pipeline.
            throw new IllegalStateException(("no scripted reply for agent '%s' — add one, or "
                    + "point llm.provider at litellm. Scripted agents: %s")
                    .formatted(request.agent(), script.keySet()));
        }

        String reply = scripted.apply(request);
        int inputTokens = estimateTokens(request.system()) + estimateTokens(request.user());
        int outputTokens = estimateTokens(reply);

        log.info("[scripted] {} — {} in, {} out (no model was called)",
                request.agent(), inputTokens, outputTokens);
        calls.incrementAndGet();

        return new ChatResponse(reply, inputTokens, outputTokens, "scripted");
    }

    @Override
    public String describe() {
        return "scripted (no model called, " + script.size() + " agents, "
                + calls.get() + " calls so far)";
    }

    public int callCount() {
        return calls.get();
    }

    static int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}

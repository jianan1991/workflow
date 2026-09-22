package com.loop.orchestrator.llm;

/**
 * The one place the pipeline talks to a model.
 *
 * <p>Everything above this interface — prompt assembly, retrieval, parsing, validation,
 * retries, cost accounting — is real code that runs identically whether a model is
 * reachable or not. That is the point: with a scripted implementation the whole pipeline
 * runs end to end on a laptop with no key and no network, and switching to the real thing
 * is a config line rather than a rewrite.
 *
 * <p><b>What a scripted model proves, and what it cannot.</b> It proves plumbing: that the
 * prompt is assembled from the right pieces, that a well-formed reply parses, that a
 * malformed one fails loudly rather than silently, that token usage reaches the cost
 * accounting. It proves nothing at all about whether an agent's answers are any good —
 * that needs a real model and a person reading the output, which is what phases 6 to 9 of
 * PHASES.md are for. Do not let a green suite here be mistaken for a working agent.
 */
public interface ChatModel {

    ChatResponse complete(ChatRequest request);

    /** Shown in logs and on the command centre, so which model ran is never a guess. */
    String describe();

    /**
     * @param agent  which pipeline stage is asking — decides the tier, and keys the
     *               per-agent cost accounting
     * @param system instructions that stay identical across calls. Kept separate from
     *               {@code user} because it is the cacheable prefix, and prompt caching is
     *               where most of a dev agent loop's input cost goes
     */
    record ChatRequest(String agent, String system, String user, int maxOutputTokens) {

        public ChatRequest {
            if (agent == null || agent.isBlank()) {
                throw new IllegalArgumentException("every request must name the agent making it");
            }
            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException(
                        "maxOutputTokens must be positive, got " + maxOutputTokens);
            }
        }
    }

    /**
     * @param inputTokens  what was actually consumed, not what was estimated. This is the
     *                     number that eventually replaces the guesses in
     *                     cost-model.properties and makes the "actual" column real
     */
    record ChatResponse(String text, int inputTokens, int outputTokens, String model,
                        double costUsd) {

        public ChatResponse(String text, int inputTokens, int outputTokens, String model) {
            this(text, inputTokens, outputTokens, model, 0.0);
        }

        public double costUsd(double inputPricePerMillion, double outputPricePerMillion) {
            return (inputTokens / 1_000_000.0) * inputPricePerMillion
                    + (outputTokens / 1_000_000.0) * outputPricePerMillion;
        }

        public ChatResponse withCost(double priced) {
            return new ChatResponse(text, inputTokens, outputTokens, model, priced);
        }
    }
}

package com.loop.orchestrator.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Derives each agent's unit cost from tokens and per-model prices, rather than carrying a
 * dollar figure somebody typed in once.
 *
 * <pre>unit cost = (input tokens / 1M x input price) + (output tokens / 1M x output price)</pre>
 *
 * <p>The point is traceability. A price change or a model swap becomes a one-line edit and
 * every downstream figure moves with it, and anyone questioning a number can see which
 * half is a fact (the price) and which half is an assumption (the tokens).
 *
 * <p><b>Output tokens include thinking.</b> Adaptive thinking is on by default on the
 * strong tier and thinking bills as output, so an agent doing real reasoning costs much
 * more on the output side than the length of its visible answer suggests.
 */
public final class AgentCostModel {

    /** Every stage {@link EstimateCalculator} prices. Missing one is a configuration bug. */
    public static final List<String> AGENTS = List.of(
            "pm", "ba", "architect", "designer", "dev-t1", "dev-t2", "reviewer", "qa");

    private static final double PER_MILLION = 1_000_000.0;

    private final Map<String, Derivation> derivations;
    private final double reworkMultiplier;

    /**
     * @param agent    the pipeline stage
     * @param model    the model its tier resolves to
     * @param dollars  the derived unit cost per run
     */
    public record Derivation(String agent,
                             String tier,
                             String model,
                             int inputTokens,
                             int outputTokens,
                             double inputPricePerM,
                             double outputPricePerM,
                             double cacheDiscount,
                             double dollars) {
    }

    public AgentCostModel(Properties model) {
        double discount = readDouble(model, "cache-discount", 1.0);
        if (discount <= 0 || discount > 1.0) {
            throw new IllegalArgumentException(
                    "cache-discount must be in (0, 1]; got " + discount);
        }

        this.reworkMultiplier = readDouble(model, "rework-multiplier", 1.45);

        Map<String, Derivation> derived = new LinkedHashMap<>();
        for (String agent : AGENTS) {
            derived.put(agent, derive(model, agent, discount));
        }
        this.derivations = Map.copyOf(derived);
    }

    private static Derivation derive(Properties model, String agent, double discount) {
        // A per-agent model wins over its tier. Tiers are a convenient default — "make all
        // the planning agents cheaper" is one edit — but the moment you want to tune the
        // one agent carrying most of the bill, naming its model directly is the honest way
        // to do it rather than inventing a tier of one.
        String override = model.getProperty("agent." + agent + ".model");
        String tier;
        String modelId;

        if (override != null && !override.isBlank()) {
            tier = "(direct)";
            modelId = override.trim();
        } else {
            tier = require(model, "agent." + agent + ".tier");
            modelId = require(model, "tier." + tier);
        }

        int inputTokens = (int) readDouble(model, "agent." + agent + ".input-tokens", -1);
        int outputTokens = (int) readDouble(model, "agent." + agent + ".output-tokens", -1);
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "agent." + agent + " is missing input-tokens or output-tokens");
        }

        double inputPrice = readDouble(model, "model." + modelId + ".input", -1);
        double outputPrice = readDouble(model, "model." + modelId + ".output", -1);
        if (inputPrice < 0 || outputPrice < 0) {
            throw new IllegalArgumentException(("no price for model '%s' (via %s, needed by "
                    + "agent '%s') — add model.%s.input and model.%s.output")
                    .formatted(modelId, "(direct)".equals(tier) ? "a direct agent.model override"
                            : "tier '" + tier + "'", agent, modelId, modelId));
        }

        // Caching only discounts input. Output is generated fresh every time, and on a
        // thinking model it is the larger half of the bill.
        double dollars = (inputTokens * discount / PER_MILLION) * inputPrice
                + (outputTokens / PER_MILLION) * outputPrice;

        return new Derivation(agent, tier, modelId, inputTokens, outputTokens,
                inputPrice, outputPrice, discount, round4(dollars));
    }

    /** The shape {@link EstimateCalculator} consumes. */
    public Map<String, Double> unitCosts() {
        Map<String, Double> costs = new LinkedHashMap<>();
        derivations.forEach((agent, d) -> costs.put(agent, d.dollars()));
        return costs;
    }

    public Derivation derivationFor(String agent) {
        Derivation derivation = derivations.get(agent);
        if (derivation == null) {
            throw new IllegalArgumentException("no such agent: " + agent);
        }
        return derivation;
    }

    public List<Derivation> derivations() {
        return AGENTS.stream().map(derivations::get).toList();
    }

    public double reworkMultiplier() {
        return reworkMultiplier;
    }

    /** Distinct models actually in play, so a reader can see the tiering at a glance. */
    public List<String> modelsInUse() {
        return derivations().stream().map(Derivation::model).distinct().sorted().toList();
    }

    private static String require(Properties model, String key) {
        String value = model.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required property: " + key);
        }
        return value.trim();
    }

    private static double readDouble(Properties model, String key, double fallback) {
        String value = model.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number: " + value, e);
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}

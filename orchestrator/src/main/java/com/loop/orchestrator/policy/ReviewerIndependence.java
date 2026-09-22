package com.loop.orchestrator.policy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks whether the reviewer is actually independent of whoever wrote the code.
 *
 * <p>The reviewer exists to answer "does this do what the ticket said". A model reviewing
 * work from its own family tends to agree with it, which makes the check weaker exactly
 * where it is load-bearing — a story clears automatic merge partly on the reviewer's say-so.
 *
 * <p><b>Uncertainty resolves toward warning.</b> When a model id cannot be attributed to a
 * known vendor, this reports NOT independent rather than staying quiet. A false warning
 * costs someone thirty seconds; a false all-clear costs an unreviewed merge, and nobody
 * finds out until a revert.
 */
public final class ReviewerIndependence {

    /**
     * Enough to attribute the models anyone here would plausibly configure. Deliberately
     * small: an unknown id is a warning, not a guess.
     */
    private static final Map<String, String> KNOWN_PREFIXES = Map.of(
            "claude", "anthropic",
            "gpt", "openai",
            "o1", "openai",
            "o3", "openai",
            "gemini", "google",
            "llama", "meta",
            "mistral", "mistral",
            "deepseek", "deepseek",
            "qwen", "alibaba");

    private ReviewerIndependence() {
    }

    /**
     * @param agentToModel every agent's resolved model, as
     *                     {@code AgentCostModel} derives it
     */
    public static Assessment assess(Map<String, String> agentToModel) {
        String reviewerModel = agentToModel.get("reviewer");
        if (reviewerModel == null || reviewerModel.isBlank()) {
            return new Assessment("(unconfigured)", "(unknown)", Map.of(), false,
                    "No model is configured for the reviewer, so nothing reviews the diff "
                            + "against the ticket at all.",
                    List.of("Set agent.reviewer.tier or agent.reviewer.model in "
                            + "cost-model.properties."));
        }

        // Only the agents that actually author code can be reviewed by the reviewer.
        Map<String, String> authors = new LinkedHashMap<>();
        agentToModel.forEach((agent, model) -> {
            if (agent.startsWith("dev-") && model != null && !model.isBlank()) {
                authors.put(agent, model);
            }
        });

        String reviewerFamily = familyOf(reviewerModel);
        Set<String> sharedWith = new LinkedHashSet<>();
        authors.forEach((agent, model) -> {
            if (familyOf(model).equals(reviewerFamily)) {
                sharedWith.add(agent + " (" + model + ")");
            }
        });

        // Independence has to be demonstrated, not merely un-contradicted. An id nobody
        // can attribute produces an empty overlap set for the same reason an unrelated
        // vendor does, and treating those two as equivalent is how a false all-clear gets
        // reported with complete confidence.
        boolean unknownFamily = "(unknown)".equals(reviewerFamily);
        boolean unattributableAuthor = authors.values().stream()
                .anyMatch(model -> "(unknown)".equals(familyOf(model)));

        if (sharedWith.isEmpty() && !unknownFamily && !unattributableAuthor) {
            return new Assessment(reviewerModel, reviewerFamily, authors, true,
                    "The reviewer is from a different vendor than every agent that writes "
                            + "code, so it has no family loyalty to the work it is checking.",
                    List.of());
        }

        if (unattributableAuthor && !unknownFamily && sharedWith.isEmpty()) {
            return new Assessment(reviewerModel, reviewerFamily, authors, false,
                    "An agent that writes code uses a model that could not be attributed to "
                            + "a vendor, so it cannot be shown the reviewer is independent of "
                            + "it. Treating that as NOT independent.",
                    List.of("Give the model an explicit vendor prefix in "
                            + "cost-model.properties, for example anthropic/... or openai/..., "
                            + "so this check can tell the two apart."));
        }
        String finding = unknownFamily
                ? ("'%s' could not be attributed to a vendor, so independence cannot be "
                + "confirmed. Treating it as NOT independent.").formatted(reviewerModel)
                : ("The reviewer (%s) shares the %s family with %s. A model tends to bless "
                + "work from its own family, and the reviewer's approval is one of the "
                + "conditions for automatic merge.")
                .formatted(reviewerModel, reviewerFamily, String.join(", ", sharedWith));

        return new Assessment(reviewerModel, reviewerFamily, authors, false, finding,
                List.of(
                        "Point agent.reviewer.model at a different vendor's model in "
                                + "cost-model.properties and route it through LiteLLM. This is "
                                + "the literal fix, and it costs a second provider key.",
                        "Or make the reviewer veto-only: let mechanical signals decide "
                                + "automatic merge (tier, CI, mutation score, files touched, QA) "
                                + "and let the reviewer block but never clear. Self-agreement "
                                + "bias makes a model too likely to APPROVE, so removing its "
                                + "power to approve removes the risk without another vendor.",
                        "Whichever you choose, watch the count of auto-approvals later "
                                + "reverted. That number, not confidence, says whether the "
                                + "reviewer is too agreeable."));
    }

    /**
     * {@code anthropic/claude-opus-5} is explicit. A bare {@code claude-opus-5} is inferred
     * from a short known list. Anything else is unknown, which counts against independence.
     */
    static String familyOf(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "(unknown)";
        }
        String id = modelId.trim().toLowerCase(Locale.ROOT);

        int slash = id.indexOf('/');
        if (slash > 0) {
            return id.substring(0, slash);
        }

        for (Map.Entry<String, String> entry : KNOWN_PREFIXES.entrySet()) {
            if (id.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "(unknown)";
    }

    /**
     * @param independent false also when it simply could not be determined — see the class
     *                    note on which way uncertainty resolves
     */
    public record Assessment(String reviewerModel,
                             String reviewerFamily,
                             Map<String, String> authorModels,
                             boolean independent,
                             String finding,
                             List<String> options) {

        /** Multi-line, for the startup log. Blank when the reviewer is independent. */
        public String warningBanner() {
            if (independent) {
                return "";
            }
            StringBuilder banner = new StringBuilder();
            banner.append("\n")
                    .append("=".repeat(78)).append("\n")
                    .append("REVIEWER INDEPENDENCE — the self-agreement mitigation is not in place\n")
                    .append("=".repeat(78)).append("\n")
                    .append(finding).append("\n");
            if (!options.isEmpty()) {
                banner.append("\nWhat to do about it:\n");
                for (int i = 0; i < options.size(); i++) {
                    banner.append("  ").append(i + 1).append(". ").append(options.get(i)).append("\n");
                }
            }
            banner.append("=".repeat(78));
            return banner.toString();
        }
    }
}

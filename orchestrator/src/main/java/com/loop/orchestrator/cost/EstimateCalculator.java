package com.loop.orchestrator.cost;

import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Story;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gate-1 forecast:
 *
 * <pre>estimate = Σ (stage runs × median cost per run) × rework multiplier</pre>
 *
 * <p>Lifted out of the stub agent on purpose. It is real logic that survives the stubs
 * being deleted, phase 1 of the plan compares a hand-priced epic against it, and the
 * multiplier has to be recalibrated monthly — none of which works while it is buried in a
 * class whose whole job is to be thrown away.
 */
public final class EstimateCalculator {

    /**
     * Only these stages loop. Planning agents run once per epic however badly the build
     * goes, so multiplying their cost by the rework rate inflates every estimate by a
     * constant and hides the thing the multiplier is meant to expose.
     */
    static final Set<String> LOOPING_STAGES = Set.of("dev", "reviewer", "qa");

    /** Minutes a person spends, forecast at the same gate and by the same method. */
    static final int GATE_1_MINUTES = 12;
    static final int GATE_2_MINUTES = 15;
    static final int GATE_3_MINUTES_PER_STORY = 8;
    static final int QUESTION_MINUTES = 18;
    static final int MANUAL_VERIFICATION_MINUTES = 22;

    /** A user interface roughly inverts the automated-to-manual ratio. */
    static final int UI_EXTRA_MANUAL_MINUTES = 25;

    private final Map<String, Double> unitCost;
    private final double reworkMultiplier;

    /**
     * @param unitCost         median cost per run, per agent. Keys: pm, ba, architect,
     *                         designer, dev-t1, dev-t2, reviewer, qa.
     * @param reworkMultiplier measured, not guessed — recalibrate monthly from the last 20
     *                         stories. It drifts whenever the pipeline improves or degrades,
     *                         which is exactly when an estimate is most likely to be quoted.
     */
    public EstimateCalculator(Map<String, Double> unitCost, double reworkMultiplier) {
        if (reworkMultiplier < 1.0) {
            // Below 1 would mean rework makes an epic cheaper. That is never true, and
            // silently accepting it produces forecasts nobody can explain later.
            throw new IllegalArgumentException(
                    "rework multiplier must be at least 1.0, got " + reworkMultiplier);
        }
        this.unitCost = Map.copyOf(unitCost);
        this.reworkMultiplier = reworkMultiplier;
    }

    public Estimate estimate(List<Story> stories, boolean hasUserInterface) {
        if (stories.isEmpty()) {
            // An epic with no stories is a decomposition failure, not a free epic.
            throw new IllegalArgumentException("cannot estimate an epic with no stories");
        }

        long tier1 = stories.stream().filter(s -> s.tier() == 1).count();
        long tier2 = stories.stream().filter(s -> s.tier() == 2).count();

        if (tier1 + tier2 != stories.size()) {
            throw new IllegalArgumentException(
                    "every story must be tier 1 or tier 2; got " + tiersIn(stories));
        }

        Map<String, Double> byStage = new LinkedHashMap<>();
        byStage.put("pm", cost("pm"));
        byStage.put("ba", cost("ba") * stories.size());
        byStage.put("architect", cost("architect"));
        if (hasUserInterface) {
            byStage.put("designer", cost("designer"));
        }
        byStage.put("dev", withRework(cost("dev-t1") * tier1 + cost("dev-t2") * tier2));
        byStage.put("reviewer", withRework(cost("reviewer") * stories.size()));
        byStage.put("qa", withRework(cost("qa") * stories.size()));

        double dollars = round2(byStage.values().stream().mapToDouble(Double::doubleValue).sum());
        byStage.replaceAll((stage, value) -> round2(value));

        return new Estimate(dollars, humanMinutes(stories.size(), hasUserInterface),
                reworkMultiplier, byStage);
    }

    /**
     * The expensive currency. At a loaded engineer rate these cost roughly eight times the
     * model spend, so an estimate that forecasts only dollars forecasts the wrong number.
     */
    int humanMinutes(int storyCount, boolean hasUserInterface) {
        int minutes = GATE_1_MINUTES
                + GATE_2_MINUTES
                + GATE_3_MINUTES_PER_STORY * storyCount
                + QUESTION_MINUTES
                + MANUAL_VERIFICATION_MINUTES;

        return hasUserInterface ? minutes + UI_EXTRA_MANUAL_MINUTES : minutes;
    }

    private double withRework(double base) {
        return base * reworkMultiplier;
    }

    private double cost(String stage) {
        Double unit = unitCost.get(stage);
        if (unit == null) {
            throw new IllegalArgumentException("no unit cost configured for stage '" + stage
                    + "' — add it, or the estimate silently under-reports");
        }
        return unit;
    }

    private static String tiersIn(List<Story> stories) {
        return stories.stream().map(s -> s.id() + "=t" + s.tier()).toList().toString();
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Only the looping stages carry the multiplier — exposed so a test can pin the rule. */
    static boolean loops(String stage) {
        return LOOPING_STAGES.contains(stage);
    }
}

package com.loop.orchestrator.cost;

import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Story;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Phase 1 — price one epic by hand, before committing to any of this.
 *
 * <pre>
 * java -cp target/classes com.loop.orchestrator.cost.CostWorksheet \
 *      --tier1 4 --tier2 2 --ui --model ../cost-model.properties
 * </pre>
 *
 * <p>Runs the same arithmetic the pipeline will use at gate 1, against unit costs you
 * filled in yourself. If the total lands far outside $15–40 per epic, the argument for the
 * project changes and should be re-made before weeks are spent on it.
 */
public final class CostWorksheet {

    private CostWorksheet() {
    }

    public static void main(String[] args) {
        int tier1 = 4;
        int tier2 = 2;
        boolean hasUi = false;
        Path modelFile = Path.of("cost-model.properties");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tier1" -> tier1 = Integer.parseInt(args[++i]);
                case "--tier2" -> tier2 = Integer.parseInt(args[++i]);
                case "--ui" -> hasUi = true;
                case "--model" -> modelFile = Path.of(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("""
                            --tier1 N   mechanical, single-module stories   (default 4)
                            --tier2 N   cross-cutting or ambiguous stories  (default 2)
                            --ui        the epic has a user-facing surface
                            --model F   cost model file (default cost-model.properties)""");
                    return;
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        Properties model = load(modelFile);
        AgentCostModel costs = new AgentCostModel(model);
        double multiplier = costs.reworkMultiplier();

        List<Story> stories = new ArrayList<>();
        for (int i = 1; i <= tier1; i++) {
            stories.add(new Story("T1-" + i, "tier 1 story " + i, 1, List.of()));
        }
        for (int i = 1; i <= tier2; i++) {
            stories.add(new Story("T2-" + i, "tier 2 story " + i, 2, List.of()));
        }

        Estimate estimate =
                new EstimateCalculator(costs.unitCosts(), multiplier).estimate(stories, hasUi);

        printDerivation(costs);
        print(estimate, stories.size(), tier1, tier2, hasUi, multiplier, modelFile);
    }

    /**
     * Shows the arithmetic, not just the answer. Anyone questioning a unit cost can see
     * which half is a fact (the price) and which half is an assumption (the tokens).
     */
    private static void printDerivation(AgentCostModel costs) {
        String rule = "=".repeat(78);
        System.out.printf("%n%s%nUNIT COSTS, DERIVED%n%s%n", rule, rule);
        System.out.printf("  %-10s %-18s %10s %10s %10s%n",
                "agent", "model", "in tok", "out tok", "$ / run");
        System.out.println("  " + "-".repeat(74));

        for (AgentCostModel.Derivation d : costs.derivations()) {
            System.out.printf("  %-10s %-18s %10s %10s %10.4f%n",
                    d.agent(), d.model(),
                    thousands(d.inputTokens()), thousands(d.outputTokens()), d.dollars());
        }

        System.out.printf("%n  models in use: %s%n", String.join(", ", costs.modelsInUse()));
        System.out.println("""

                  Output tokens include thinking, which bills as output. An agent doing real
                  reasoning costs far more on the output side than its visible answer suggests.

                  The prices are facts. The token counts are assumptions - measure them with
                  the token counting endpoint on one real prompt per agent, carrying the
                  context it would actually receive.""");
    }

    private static String thousands(int tokens) {
        return tokens >= 1000 ? (tokens / 1000) + "k" : String.valueOf(tokens);
    }

    private static void print(Estimate estimate, int total, int tier1, int tier2,
                              boolean hasUi, double multiplier, Path modelFile) {

        String rule = "=".repeat(62);
        System.out.printf("%n%s%n", rule);
        System.out.printf("ONE EPIC - %d stories (%d tier 1, %d tier 2)%s%n",
                total, tier1, tier2, hasUi ? ", with a user interface" : ", backend only");
        System.out.printf("model: %s%n%s%n%n", modelFile, rule);

        System.out.println("MODEL SPEND");
        estimate.byStage().forEach((stage, cost) -> {
            boolean loops = EstimateCalculator.loops(stage);
            System.out.printf("  %-12s %8.2f%s%n", stage, cost,
                    loops ? "   x%.2f rework applied".formatted(multiplier) : "");
        });
        System.out.printf("  %-12s %8.2f%n", "TOTAL", estimate.dollars());

        System.out.printf("%nHUMAN TIME%n  %-12s %8d minutes  (%.1f h)%n",
                "TOTAL", estimate.humanMinutes(), estimate.humanMinutes() / 60.0);

        System.out.printf("%n%s%nREAD THIS BEFORE QUOTING THE NUMBER%n%s%n", rule, rule);
        System.out.printf("""
                The $%.2f is the cheap half. At a loaded engineer rate those %d minutes
                cost several times more, and they are the half that does not fall as the
                pipeline improves.

                The rework multiplier of %.2f is the biggest lever here and it is a guess
                until 20 real stories have run. Overruns in this pipeline are almost never
                a bad unit price - they are extra trips round the loop.

                Phase 1 passes when you would defend both numbers. Stop if the total is far
                outside $15-40: the argument for the project changes and should be re-made.
                %n""", estimate.dollars(), estimate.humanMinutes(), multiplier);
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not read the cost model at " + file.toAbsolutePath(), e);
        }
    }
}

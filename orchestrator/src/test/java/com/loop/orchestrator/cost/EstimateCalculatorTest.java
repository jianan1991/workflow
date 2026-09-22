package com.loop.orchestrator.cost;

import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EstimateCalculatorTest {

    private static final Map<String, Double> UNIT_COST = Map.of(
            "pm", 0.71,
            "ba", 0.38,
            "architect", 1.10,
            "designer", 0.94,
            "dev-t1", 0.42,
            "dev-t2", 1.85,
            "reviewer", 0.11,
            "qa", 0.38);

    /** Shared with {@link AgentCostModelTest}, which prices the checked-in cost model. */
    static List<Story> sixStories() {
        return SIX_STORIES;
    }

    private static final List<Story> SIX_STORIES = List.of(
            new Story("E-1", "one", 1, List.of()),
            new Story("E-2", "two", 1, List.of()),
            new Story("E-3", "three", 1, List.of()),
            new Story("E-4", "four", 1, List.of()),
            new Story("E-5", "five", 2, List.of()),
            new Story("E-6", "six", 2, List.of()));

    private final EstimateCalculator calculator = new EstimateCalculator(UNIT_COST, 1.45);

    @Test
    @DisplayName("the worked example adds up to the figure quoted at gate 1")
    void matchesTheWorkedExample() {
        // pm 0.71 + ba 2.28 + architect 1.10 + designer 0.94        = 5.03
        // dev (4 x 0.42 + 2 x 1.85) x 1.45 = 5.38 x 1.45            = 7.80
        // reviewer 6 x 0.11 x 1.45                                  = 0.96
        // qa 6 x 0.38 x 1.45                                        = 3.31
        Estimate estimate = calculator.estimate(SIX_STORIES, true);

        assertThat(estimate.dollars()).isCloseTo(17.10, within(0.02));
    }

    @Test
    @DisplayName("only build and check stages carry the rework multiplier")
    void planningStagesDoNotLoop() {
        Estimate once = new EstimateCalculator(UNIT_COST, 1.0).estimate(SIX_STORIES, true);
        Estimate twice = new EstimateCalculator(UNIT_COST, 2.0).estimate(SIX_STORIES, true);

        assertThat(twice.byStage().get("pm"))
                .as("the PM runs once per epic however badly the build goes")
                .isEqualTo(once.byStage().get("pm"));
        assertThat(twice.byStage().get("ba")).isEqualTo(once.byStage().get("ba"));
        assertThat(twice.byStage().get("architect")).isEqualTo(once.byStage().get("architect"));

        assertThat(twice.byStage().get("dev"))
                .isCloseTo(once.byStage().get("dev") * 2, within(0.01));
        assertThat(twice.byStage().get("qa"))
                .isCloseTo(once.byStage().get("qa") * 2, within(0.01));
        assertThat(twice.byStage().get("reviewer"))
                .isCloseTo(once.byStage().get("reviewer") * 2, within(0.01));
    }

    @Test
    @DisplayName("a tier 2 story costs more than a tier 1 story, and the split is what drives it")
    void tierDrivesDevCost() {
        List<Story> allTier1 = List.of(
                new Story("A", "a", 1, List.of()), new Story("B", "b", 1, List.of()));
        List<Story> allTier2 = List.of(
                new Story("A", "a", 2, List.of()), new Story("B", "b", 2, List.of()));

        assertThat(calculator.estimate(allTier2, false).byStage().get("dev"))
                .isGreaterThan(calculator.estimate(allTier1, false).byStage().get("dev"));
    }

    @Test
    @DisplayName("a backend-only epic is not charged for the designer")
    void designerOnlyWhenThereIsAnInterface() {
        Estimate backend = calculator.estimate(SIX_STORIES, false);
        Estimate withUi = calculator.estimate(SIX_STORIES, true);

        assertThat(backend.byStage()).doesNotContainKey("designer");
        assertThat(withUi.byStage()).containsKey("designer");
        assertThat(withUi.dollars()).isGreaterThan(backend.dollars());
    }

    @Test
    @DisplayName("the multiplier used is reported, so a quoted figure can be traced back")
    void reportsTheMultiplier() {
        assertThat(calculator.estimate(SIX_STORIES, true).reworkMultiplier()).isEqualTo(1.45);
    }

    @Nested
    @DisplayName("human minutes")
    class HumanMinutes {

        @Test
        @DisplayName("grow with the story count, because gate 3 fires once per story")
        void scaleWithStories() {
            int two = calculator.humanMinutes(2, false);
            int six = calculator.humanMinutes(6, false);

            assertThat(six - two)
                    .isEqualTo(4 * EstimateCalculator.GATE_3_MINUTES_PER_STORY);
        }

        @Test
        @DisplayName("an interface adds manual verification, because UI inverts the automated ratio")
        void uiAddsManualTime() {
            assertThat(calculator.humanMinutes(6, true))
                    .isEqualTo(calculator.humanMinutes(6, false)
                            + EstimateCalculator.UI_EXTRA_MANUAL_MINUTES);
        }

        @Test
        @DisplayName("are on the estimate at all, because forecasting only dollars forecasts the wrong number")
        void areIncludedInTheEstimate() {
            assertThat(calculator.estimate(SIX_STORIES, true).humanMinutes()).isEqualTo(140);
        }
    }

    @Nested
    @DisplayName("refuses to produce a number it cannot stand behind")
    class Rejections {

        @Test
        @DisplayName("an epic with no stories is a decomposition failure, not a free epic")
        void emptyEpic() {
            assertThatThrownBy(() -> calculator.estimate(List.of(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no stories");
        }

        @Test
        @DisplayName("a multiplier below 1 would mean rework makes an epic cheaper")
        void multiplierBelowOne() {
            assertThatThrownBy(() -> new EstimateCalculator(UNIT_COST, 0.9))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1.0");
        }

        @Test
        @DisplayName("a tier nobody defined is rejected rather than quietly priced as tier 1")
        void unknownTier() {
            List<Story> withTier3 = List.of(new Story("X", "x", 3, List.of()));

            assertThatThrownBy(() -> calculator.estimate(withTier3, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tier 1 or tier 2");
        }

        @Test
        @DisplayName("a missing unit cost fails loudly instead of under-reporting")
        void missingUnitCost() {
            EstimateCalculator incomplete =
                    new EstimateCalculator(Map.of("pm", 0.71), 1.45);

            assertThatThrownBy(() -> incomplete.estimate(SIX_STORIES, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no unit cost configured");
        }
    }

    @Test
    @DisplayName("the looping-stage rule is pinned, because getting it wrong inflates every estimate")
    void loopingStagesArePinned() {
        assertThat(EstimateCalculator.loops("dev")).isTrue();
        assertThat(EstimateCalculator.loops("qa")).isTrue();
        assertThat(EstimateCalculator.loops("reviewer")).isTrue();
        assertThat(EstimateCalculator.loops("pm")).isFalse();
        assertThat(EstimateCalculator.loops("ba")).isFalse();
        assertThat(EstimateCalculator.loops("architect")).isFalse();
        assertThat(EstimateCalculator.loops("designer")).isFalse();
    }
}

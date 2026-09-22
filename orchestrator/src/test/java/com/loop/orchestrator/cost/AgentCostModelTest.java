package com.loop.orchestrator.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AgentCostModelTest {

    private static final String MINIMAL = """
            model.claude-opus-5.input=5.00
            model.claude-opus-5.output=25.00
            model.claude-sonnet-5.input=2.00
            model.claude-sonnet-5.output=10.00
            model.claude-haiku-4-5.input=1.00
            model.claude-haiku-4-5.output=5.00

            tier.strong=claude-opus-5
            tier.mid=claude-sonnet-5
            tier.cheap=claude-haiku-4-5
            tier.review=claude-sonnet-5

            agent.pm.tier=strong
            agent.pm.input-tokens=8000
            agent.pm.output-tokens=2000
            agent.ba.tier=strong
            agent.ba.input-tokens=6000
            agent.ba.output-tokens=1500
            agent.architect.tier=strong
            agent.architect.input-tokens=15000
            agent.architect.output-tokens=3000
            agent.designer.tier=strong
            agent.designer.input-tokens=10000
            agent.designer.output-tokens=4000
            agent.dev-t1.tier=cheap
            agent.dev-t1.input-tokens=150000
            agent.dev-t1.output-tokens=15000
            agent.dev-t2.tier=strong
            agent.dev-t2.input-tokens=400000
            agent.dev-t2.output-tokens=40000
            agent.reviewer.tier=review
            agent.reviewer.input-tokens=20000
            agent.reviewer.output-tokens=2000
            agent.qa.tier=mid
            agent.qa.input-tokens=25000
            agent.qa.output-tokens=3000

            rework-multiplier=1.45
            """;

    private static AgentCostModel from(String properties) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(properties));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new AgentCostModel(p);
    }

    @Test
    @DisplayName("derives a unit cost from tokens times price, not from a typed-in figure")
    void derivesFromTokensAndPrice() {
        // 8k input at $5/M = $0.040, 2k output at $25/M = $0.050
        assertThat(from(MINIMAL).derivationFor("pm").dollars()).isCloseTo(0.09, within(0.0001));
    }

    @Test
    @DisplayName("resolves each agent through its tier to an actual model")
    void resolvesTiers() {
        AgentCostModel costs = from(MINIMAL);

        assertThat(costs.derivationFor("pm").model()).isEqualTo("claude-opus-5");
        assertThat(costs.derivationFor("dev-t1").model()).isEqualTo("claude-haiku-4-5");
        assertThat(costs.derivationFor("qa").model()).isEqualTo("claude-sonnet-5");
        assertThat(costs.modelsInUse())
                .containsExactly("claude-haiku-4-5", "claude-opus-5", "claude-sonnet-5");
    }

    @Test
    @DisplayName("the tier 2 dev agent dominates, which is where cost attention belongs")
    void devTierTwoDominates() {
        AgentCostModel costs = from(MINIMAL);
        double devT2 = costs.derivationFor("dev-t2").dollars();

        assertThat(devT2).isGreaterThan(
                costs.derivationFor("pm").dollars()
                        + costs.derivationFor("ba").dollars()
                        + costs.derivationFor("architect").dollars()
                        + costs.derivationFor("designer").dollars()
                        + costs.derivationFor("reviewer").dollars()
                        + costs.derivationFor("qa").dollars());
    }

    @Test
    @DisplayName("changing a tier's model moves every agent on that tier")
    void swappingATierMovesItsAgents() {
        double before = from(MINIMAL).derivationFor("qa").dollars();
        double after = from(MINIMAL.replace("tier.mid=claude-sonnet-5",
                "tier.mid=claude-haiku-4-5")).derivationFor("qa").dollars();

        assertThat(after)
                .as("a one-line edit must move the derived figure, or the model is decorative")
                .isLessThan(before);
    }

    @Test
    @DisplayName("produces the map the estimator consumes, covering every stage it prices")
    void producesEveryStageTheEstimatorNeeds() {
        assertThat(from(MINIMAL).unitCosts()).containsOnlyKeys(
                "pm", "ba", "architect", "designer", "dev-t1", "dev-t2", "reviewer", "qa");
    }

    @Nested
    @DisplayName("per-agent model override")
    class DirectModel {

        @Test
        @DisplayName("a named model on one agent wins over its tier")
        void overrideWinsOverTier() {
            AgentCostModel costs = from(MINIMAL + "\nagent.dev-t2.model=claude-sonnet-5\n");

            assertThat(costs.derivationFor("dev-t2").model()).isEqualTo("claude-sonnet-5");
            assertThat(costs.derivationFor("dev-t2").tier()).isEqualTo("(direct)");
        }

        @Test
        @DisplayName("overriding one agent leaves the others on the tier alone")
        void overrideIsNarrow() {
            AgentCostModel costs = from(MINIMAL + "\nagent.dev-t2.model=claude-sonnet-5\n");

            assertThat(costs.derivationFor("pm").model())
                    .as("dev-t2 shares the strong tier with pm; moving one must not move both")
                    .isEqualTo("claude-opus-5");
        }

        @Test
        @DisplayName("pinning the most expensive agent to a cheaper model moves the whole bill")
        void overrideChangesCost() {
            double before = from(MINIMAL).derivationFor("dev-t2").dollars();
            double after = from(MINIMAL + "\nagent.dev-t2.model=claude-sonnet-5\n")
                    .derivationFor("dev-t2").dollars();

            assertThat(after).isLessThan(before);
        }

        @Test
        @DisplayName("an unpriced override says it came from a direct override, not a tier")
        void unpricedOverrideIsClear() {
            assertThatThrownBy(() -> from(MINIMAL + "\nagent.qa.model=claude-imaginary-9\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("direct agent.model override");
        }
    }

    @Nested
    @DisplayName("prompt caching")
    class Caching {

        @Test
        @DisplayName("discounts input only — output is generated fresh every time")
        void discountsInputOnly() {
            AgentCostModel full = from(MINIMAL);
            AgentCostModel halved = from(MINIMAL + "\ncache-discount=0.5\n");

            // pm: input $0.040 halves to $0.020; output $0.050 is untouched.
            assertThat(halved.derivationFor("pm").dollars()).isCloseTo(0.07, within(0.0001));
            assertThat(full.derivationFor("pm").dollars()).isCloseTo(0.09, within(0.0001));
        }

        @Test
        @DisplayName("defaults to no discount, because quoting an unmeasured saving is how an estimate stops being defensible")
        void defaultsToNoDiscount() {
            assertThat(from(MINIMAL).derivationFor("pm").cacheDiscount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("rejects a discount above 1, which would mean caching costs more")
        void rejectsDiscountAboveOne() {
            assertThatThrownBy(() -> from(MINIMAL + "\ncache-discount=1.5\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cache-discount");
        }
    }

    @Nested
    @DisplayName("refuses to produce a figure it cannot justify")
    class Rejections {

        @Test
        @DisplayName("a tier pointing at a model with no price fails loudly")
        void unpricedModel() {
            assertThatThrownBy(() -> from(MINIMAL.replace(
                    "tier.mid=claude-sonnet-5", "tier.mid=claude-imaginary-9")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claude-imaginary-9");
        }

        @Test
        @DisplayName("an agent with no tier fails rather than being silently priced at zero")
        void missingTier() {
            assertThatThrownBy(() -> from(MINIMAL.replace("agent.qa.tier=mid", "")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agent.qa.tier");
        }

        @Test
        @DisplayName("an agent with no token estimate fails rather than costing nothing")
        void missingTokens() {
            assertThatThrownBy(() -> from(MINIMAL.replace("agent.qa.input-tokens=25000", "")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("agent.qa");
        }

        @Test
        @DisplayName("a non-numeric price is reported with the key, not swallowed")
        void nonNumericPrice() {
            assertThatThrownBy(() -> from(MINIMAL.replace(
                    "model.claude-opus-5.input=5.00", "model.claude-opus-5.input=free")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model.claude-opus-5.input");
        }
    }

    @Test
    @DisplayName("the checked-in cost model is valid and prices a six-story epic in a defensible range")
    void checkedInModelIsValid() {
        Path file = Path.of("..", "cost-model.properties");
        Properties properties = new Properties();
        try {
            properties.load(Files.newBufferedReader(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cost-model.properties must stay loadable", e);
        }

        AgentCostModel costs = new AgentCostModel(properties);
        var estimate = new EstimateCalculator(costs.unitCosts(), costs.reworkMultiplier())
                .estimate(EstimateCalculatorTest.sixStories(), true);

        assertThat(estimate.dollars())
                .as("a six-story epic should land in the tens of dollars — far outside that "
                        + "band means the argument for the project has changed")
                .isBetween(5.0, 60.0);
    }
}

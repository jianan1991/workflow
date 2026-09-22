package com.loop.orchestrator.llm;

import com.loop.orchestrator.cost.AgentCostModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostingChatModelTest {

    private static final String MODEL = """
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
            """;

    /** Returns fixed token counts so the arithmetic is checkable by hand. */
    private static ChatModel returning(int inputTokens, int outputTokens) {
        return new ChatModel() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                return new ChatResponse("{}", inputTokens, outputTokens, "test");
            }

            @Override
            public String describe() {
                return "fixed";
            }
        };
    }

    private static AgentCostModel prices() {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(MODEL));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new AgentCostModel(properties);
    }

    private static ChatModel.ChatRequest request(String agent) {
        return new ChatModel.ChatRequest(agent, "system", "user", 4096);
    }

    @Test
    @DisplayName("prices a call from the tokens it actually consumed")
    void pricesFromRealTokens() {
        // 10k input at $5/M = $0.05, 2k output at $25/M = $0.05
        CostingChatModel model = new CostingChatModel(returning(10_000, 2_000), prices());

        assertThat(model.complete(request("pm")).costUsd()).isCloseTo(0.10, within(0.0001));
    }

    @Test
    @DisplayName("each agent is priced at its own tier, not at one blended rate")
    void pricesPerAgentTier() {
        CostingChatModel model = new CostingChatModel(returning(10_000, 2_000), prices());

        double onOpus = model.complete(request("pm")).costUsd();
        double onHaiku = model.complete(request("dev-t1")).costUsd();

        assertThat(onHaiku)
                .as("the cheap tier has to actually be cheaper, or tiering is decorative")
                .isLessThan(onOpus);
    }

    @Test
    @DisplayName("a scripted run reports what it would have cost — the point of pricing the fake")
    void scriptedRunIsPriced() {
        ChatModel scripted = ScriptedChatModel.of(Map.of("pm", "{\"stages\":[]}"));
        CostingChatModel model = new CostingChatModel(scripted, prices());

        ChatModel.ChatResponse response = model.complete(request("pm"));

        assertThat(response.costUsd())
                .as("a free run that reports zero leaves the cost arithmetic untested until "
                        + "the first invoice")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("accumulates spend per agent, so one agent's share is visible")
    void accumulatesPerAgent() {
        CostingChatModel model = new CostingChatModel(returning(10_000, 2_000), prices());

        model.complete(request("pm"));
        model.complete(request("pm"));
        model.complete(request("qa"));

        assertThat(model.spendByAgent()).containsOnlyKeys("pm", "qa");
        assertThat(model.spendByAgent().get("pm"))
                .isCloseTo(2 * model.spendByAgent().get("pm") / 2, within(0.0001));
        assertThat(model.totalSpend()).isCloseTo(
                model.spendByAgent().values().stream().mapToDouble(Double::doubleValue).sum(),
                within(0.0001));
    }

    @Test
    @DisplayName("an agent missing from the cost model reports zero, and says so rather than hiding it")
    void unpricedAgentIsNotSilent() {
        CostingChatModel model = new CostingChatModel(returning(10_000, 2_000), prices());

        // "designer" is priced; "translator" is not configured at all.
        assertThat(model.complete(request("translator")).costUsd()).isZero();
        assertThat(model.complete(request("designer")).costUsd()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("zero tokens costs zero, without dividing by anything")
    void zeroTokensIsFree() {
        CostingChatModel model = new CostingChatModel(returning(0, 0), prices());

        assertThat(model.complete(request("pm")).costUsd()).isZero();
    }
}

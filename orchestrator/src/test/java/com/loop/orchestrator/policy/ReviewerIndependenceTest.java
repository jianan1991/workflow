package com.loop.orchestrator.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewerIndependenceTest {

    private static Map<String, String> agents(String reviewer, String devT1, String devT2) {
        Map<String, String> agents = new LinkedHashMap<>();
        agents.put("pm", "anthropic/claude-opus-5");
        agents.put("ba", "anthropic/claude-opus-5");
        agents.put("architect", "anthropic/claude-opus-5");
        agents.put("dev-t1", devT1);
        agents.put("dev-t2", devT2);
        agents.put("reviewer", reviewer);
        agents.put("qa", "anthropic/claude-sonnet-5");
        return agents;
    }

    @Test
    @DisplayName("the shipped configuration is flagged — every tier is Anthropic today")
    void currentConfigurationIsFlagged() {
        ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(
                agents("anthropic/claude-sonnet-5",
                        "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5"));

        assertThat(assessment.independent()).isFalse();
        assertThat(assessment.finding()).contains("anthropic", "dev-t1", "dev-t2");
        assertThat(assessment.options())
                .as("a warning with no remedy is just noise")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a different vendor for the reviewer clears it")
    void differentVendorIsIndependent() {
        ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(
                agents("openai/gpt-5", "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5"));

        assertThat(assessment.independent()).isTrue();
        assertThat(assessment.warningBanner()).isEmpty();
    }

    @Test
    @DisplayName("a different Claude model does NOT clear it — same family is the point")
    void differentModelSameFamilyStillFlagged() {
        ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(
                agents("anthropic/claude-opus-5",
                        "anthropic/claude-haiku-4-5", "anthropic/claude-sonnet-5"));

        assertThat(assessment.independent())
                .as("Opus reviewing Haiku is still one vendor's lineage checking its own work")
                .isFalse();
    }

    @Test
    @DisplayName("sharing a family with only one of the two dev agents is still flagged")
    void partialOverlapIsFlagged() {
        ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(
                agents("openai/gpt-5", "openai/gpt-5-mini", "anthropic/claude-opus-5"));

        assertThat(assessment.independent()).isFalse();
        assertThat(assessment.finding()).contains("dev-t1");
    }

    @Nested
    @DisplayName("uncertainty resolves toward warning")
    class Uncertainty {

        @Test
        @DisplayName("a model nobody can attribute is treated as NOT independent")
        void unknownModelIsFlagged() {
            ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(
                    agents("some-internal-model-v3",
                            "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5"));

            assertThat(assessment.independent())
                    .as("a false all-clear costs an unreviewed merge; a false warning costs "
                            + "thirty seconds")
                    .isFalse();
            assertThat(assessment.finding()).contains("could not be attributed");
        }

        @Test
        @DisplayName("no reviewer configured at all is the loudest case")
        void noReviewerConfigured() {
            Map<String, String> agents = agents("anthropic/claude-sonnet-5",
                    "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5");
            agents.remove("reviewer");

            ReviewerIndependence.Assessment assessment = ReviewerIndependence.assess(agents);

            assertThat(assessment.independent()).isFalse();
            assertThat(assessment.finding()).contains("nothing reviews the diff");
        }
    }

    @Nested
    @DisplayName("family attribution")
    class Families {

        @Test
        @DisplayName("an explicit vendor prefix is taken at its word")
        void explicitPrefix() {
            assertThat(ReviewerIndependence.familyOf("anthropic/claude-opus-5"))
                    .isEqualTo("anthropic");
            assertThat(ReviewerIndependence.familyOf("openai/gpt-5")).isEqualTo("openai");
        }

        @Test
        @DisplayName("a bare model id is inferred from a short known list")
        void bareModelId() {
            assertThat(ReviewerIndependence.familyOf("claude-sonnet-5")).isEqualTo("anthropic");
            assertThat(ReviewerIndependence.familyOf("gpt-5")).isEqualTo("openai");
            assertThat(ReviewerIndependence.familyOf("gemini-2-pro")).isEqualTo("google");
        }

        @Test
        @DisplayName("anything else is unknown rather than guessed at")
        void unknownIsNotGuessed() {
            assertThat(ReviewerIndependence.familyOf("acme-coder-9")).isEqualTo("(unknown)");
            assertThat(ReviewerIndependence.familyOf("")).isEqualTo("(unknown)");
            assertThat(ReviewerIndependence.familyOf(null)).isEqualTo("(unknown)");
        }
    }

    @Test
    @DisplayName("the banner names the problem and what to do, not just that something is wrong")
    void bannerIsActionable() {
        String banner = ReviewerIndependence.assess(
                        agents("anthropic/claude-sonnet-5",
                                "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5"))
                .warningBanner();

        assertThat(banner).contains("REVIEWER INDEPENDENCE");
        assertThat(banner).contains("agent.reviewer.model");
        assertThat(banner).contains("veto-only");
        assertThat(banner)
                .as("the measurement matters more than the configuration choice")
                .contains("reverted");
    }
}

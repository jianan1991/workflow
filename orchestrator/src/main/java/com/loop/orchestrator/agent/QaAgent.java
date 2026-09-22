package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.CriterionVerdict;
import com.loop.orchestrator.domain.Domain.Observation;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Judges what the running system actually did against what the criteria said it should.
 *
 * <p><b>It never sees the developer agent's tests.</b> If the agent writes the tests and
 * QA confirms they pass, nothing has been measured — the same author has graded its own
 * exam. QA works from observations captured by exercising the software: real responses,
 * real logs, real rows.
 *
 * <p>Running the software is deterministic work and belongs in an activity. This class
 * only judges, which is what keeps it from reporting on tests it never ran.
 *
 * <p>The routing is the whole design. A code defect goes back to the dev agent and no
 * person sees it; a criterion that was simply wrong goes back to the PM, because sending
 * it to a developer buys a second wrong implementation at full price; something QA cannot
 * verify goes to a person's checklist without failing the story.
 */
public class QaAgent {

    private static final Logger log = LoggerFactory.getLogger(QaAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "qa";

    private static final String SYSTEM = """
            You judge what a running system actually did against its acceptance criteria.

            You are given criteria and observations captured by exercising the software.
            Judge only from the observations. If nothing in them speaks to a criterion,
            that criterion is UNVERIFIABLE - never infer a pass from code that looks right
            or from an absence of errors.

            Give every criterion exactly one outcome:
              PASS            - an observation shows it holds
              CODE_DEFECT     - an observation shows it does not hold; the code is wrong
              CRITERION_WRONG - the code does what the criterion says, and the criterion
                                asks for the wrong thing, or the system does something
                                dangerous that no criterion covers
              UNVERIFIABLE    - the observations do not settle it

            CRITERION_WRONG is the one people get wrong. Use it when the implementation is
            faithful but the requirement is not what anybody wanted - that goes back to
            product, not to a developer, because rebuilding faithful code against the same
            wrong requirement changes nothing.

            Quote the observation behind every verdict. A verdict with no evidence is a
            guess, and a guess here is indistinguishable from a test.

            Reply with JSON only, no prose and no code fence:
            {"verdicts":[{"criterionId":"AC-1","outcome":"PASS","evidence":"..."}],
             "concerns":[{"outcome":"CRITERION_WRONG","evidence":"..."}]}
            """;

    private final ChatModel model;
    private final int maxOutputTokens;

    public QaAgent(ChatModel model, int maxOutputTokens) {
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    public Result judge(Story story, List<AcceptanceCriterion> criteria,
                        List<Observation> observations) {

        if (criteria.isEmpty()) {
            throw new IllegalArgumentException(
                    "QA cannot judge " + story.id() + " with no acceptance criteria");
        }

        ChatModel.ChatResponse response = model.complete(new ChatModel.ChatRequest(
                NAME, SYSTEM, buildPrompt(story, criteria, observations), maxOutputTokens));

        Result result = parse(response.text(), story.id(), criteria);
        log.info("[qa] {} — {} pass, {} defect, {} criterion-wrong, {} unverifiable, {} in / {} out",
                story.id(), result.count(QaOutcome.PASS), result.count(QaOutcome.CODE_DEFECT),
                result.count(QaOutcome.CRITERION_WRONG), result.count(QaOutcome.UNVERIFIABLE),
                response.inputTokens(), response.outputTokens());
        return result;
    }

    String buildPrompt(Story story, List<AcceptanceCriterion> criteria,
                       List<Observation> observations) {

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Story ").append(story.id()).append(": ").append(story.title())
                .append("\n\n# Acceptance criteria\n\n");

        criteria.forEach(criterion -> prompt
                .append("- ").append(criterion.id()).append(": ").append(criterion.text())
                .append("  [").append(criterion.testability()).append("]\n"));

        prompt.append("\n# What was observed when the system ran\n\n");
        if (observations.isEmpty()) {
            // Without this, a model handed no observations will happily reason from the
            // criteria alone and report passes for a run that never happened.
            prompt.append("Nothing was captured. Every criterion is UNVERIFIABLE — do not "
                    + "infer any outcome from the criteria themselves.\n");
        } else {
            observations.forEach(observation -> prompt
                    .append("## ").append(observation.what()).append("\n```\n")
                    .append(observation.evidence()).append("\n```\n\n"));
        }

        return prompt.toString();
    }

    static Result parse(String reply, String storyId, List<AcceptanceCriterion> criteria) {
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, storyId,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        Set<String> expected = new LinkedHashSet<>();
        criteria.forEach(criterion -> expected.add(criterion.id()));

        List<CriterionVerdict> verdicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode node : root.path("verdicts")) {
            String id = node.path("criterionId").asText("").trim();
            if (!expected.contains(id)) {
                throw new AgentReplyException(NAME, storyId,
                        "verdict for '%s', which is not a criterion on this story %s"
                                .formatted(id, expected), null);
            }
            if (!seen.add(id)) {
                throw new AgentReplyException(NAME, storyId,
                        "two verdicts for " + id, null);
            }
            verdicts.add(new CriterionVerdict(id, outcome(node, storyId, id),
                    node.path("evidence").asText("").trim()));
        }

        // Every criterion must be answered. A criterion silently missing from the reply is
        // one nobody checked, and it would merge looking exactly like one that passed.
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seen);
        if (!missing.isEmpty()) {
            throw new AgentReplyException(NAME, storyId,
                    "no verdict for " + missing + " — a criterion nobody checked must not "
                            + "merge looking like one that passed", null);
        }

        List<CriterionVerdict> concerns = new ArrayList<>();
        for (JsonNode node : root.path("concerns")) {
            // Concerns have no criterion by definition — they are what nobody thought to
            // ask for, which is exactly the category worth surfacing.
            concerns.add(new CriterionVerdict(null, outcome(node, storyId, "concern"),
                    node.path("evidence").asText("").trim()));
        }

        return new Result(List.copyOf(verdicts), List.copyOf(concerns));
    }

    private static QaOutcome outcome(JsonNode node, String storyId, String where) {
        String raw = node.path("outcome").asText("");
        try {
            return QaOutcome.valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            // Defaulting to PASS would merge a broken story; defaulting to a failure would
            // send working code round the loop again. Neither is recoverable from silently.
            throw new AgentReplyException(NAME, storyId,
                    "%s had outcome '%s'; expected PASS, CODE_DEFECT, CRITERION_WRONG or "
                            .formatted(where, raw) + "UNVERIFIABLE", e);
        }
    }

    /**
     * @param concerns things no criterion covers — the fail-open behaviour nobody specified.
     *                 These route to the PM, because there is no criterion to fix.
     */
    public record Result(List<CriterionVerdict> verdicts, List<CriterionVerdict> concerns) {

        public long count(QaOutcome outcome) {
            return verdicts.stream().filter(v -> v.outcome() == outcome).count()
                    + concerns.stream().filter(v -> v.outcome() == outcome).count();
        }

        /**
         * Where this story goes next. Order matters: a wrong criterion outranks a code
         * defect, because rebuilding against a requirement nobody wanted is waste however
         * many other things are also broken.
         */
        public QaOutcome route() {
            if (count(QaOutcome.CRITERION_WRONG) > 0) {
                return QaOutcome.CRITERION_WRONG;
            }
            if (count(QaOutcome.CODE_DEFECT) > 0) {
                return QaOutcome.CODE_DEFECT;
            }
            // Unverifiable items do not fail a story — they become a person's checklist,
            // and they were already classified and shown at the epic gate.
            return QaOutcome.PASS;
        }

        public long unverifiable() {
            return count(QaOutcome.UNVERIFIABLE);
        }
    }
}

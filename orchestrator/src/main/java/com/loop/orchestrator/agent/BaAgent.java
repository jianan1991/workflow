package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Testability;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one settled journey stage into testable acceptance criteria.
 *
 * <p>Works a stage at a time and asks implementation questions: given this, what exactly
 * must be true for it to be done. It never redesigns the flow — that is the PM's job, and
 * if both agents are asking the same question one of them is out of its lane.
 *
 * <p>The judgement that makes or breaks this agent is knowing when it does not know.
 * Explicit criteria it cannot write are the signal to ask a person; a non-obvious decision
 * with no knowledge-base citation behind it is the same signal. Inventing either is how a
 * wrong requirement reaches production with tests asserting it.
 */
public class BaAgent {

    private static final Logger log = LoggerFactory.getLogger(BaAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "ba";

    private static final String SYSTEM = """
            You turn one stage of a customer journey into acceptance criteria.

            A criterion states what must be observably true, in terms someone could check.
            "Rate limiting works" is not a criterion. "The 101st request in a 60 second
            window returns 429 with a Retry-After header" is.

            Classify each one by how it can be verified:
              AUTOMATED   - a test can boot the app, call it and assert
              OBSERVABLE  - output can be captured but not judged by a machine
              UNREACHABLE - needs a third-party sandbox, production data, or a browser
              HUMAN_ONLY  - judgement: wording, feel, whether a value is sensible

            Be honest about this. Marking something AUTOMATED that is not means QA reports
            a pass it never checked.

            Cite the knowledge-base file behind any non-obvious decision, using the exact
            filename given to you. If a decision is non-obvious and you have no source for
            it, say so in `unanswerable` rather than inventing the answer - a question is
            cheap, a wrong requirement built and tested is not.

            Do not redesign the flow. Do not propose an implementation.

            Reply with JSON only, no prose and no code fence:
            {"criteria":[{"text":"...","testability":"AUTOMATED","sources":["kb/x.md"]}],
             "unanswerable":["..."]}
            """;

    private final ChatModel model;
    private final KnowledgeIndexer knowledge;
    private final int maxOutputTokens;

    public BaAgent(ChatModel model, KnowledgeIndexer knowledge, int maxOutputTokens) {
        this.model = model;
        this.knowledge = knowledge;
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * @return criteria plus anything the agent could not settle. A non-empty
     *         {@code unanswerable} is what raises a question rather than a story.
     */
    public Result writeCriteria(Epic epic, JourneyStage stage) {
        List<Hit> context = knowledge.search(stage.name() + "\n" + stage.description(), 6);

        ChatModel.ChatResponse response = model.complete(new ChatModel.ChatRequest(
                NAME, SYSTEM, buildPrompt(epic, stage, context), maxOutputTokens));

        Result result = parse(response.text(), epic.id(), stage.index());
        log.info("[ba] {} stage {} — {} criteria ({} automated), {} unanswerable, {} in / {} out",
                epic.id(), stage.index(), result.criteria().size(),
                result.split().automated(), result.unanswerable().size(),
                response.inputTokens(), response.outputTokens());
        return result;
    }

    String buildPrompt(Epic epic, JourneyStage stage, List<Hit> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Epic ").append(epic.id()).append(": ").append(epic.title())
                .append("\n\n").append(epic.body()).append("\n\n");

        prompt.append("# The stage you are writing criteria for\n\n")
                .append("Stage ").append(stage.index()).append(": ").append(stage.name())
                .append("\n").append(stage.description()).append("\n");

        if (!context.isEmpty()) {
            prompt.append("\n# Knowledge base\n\nCite these by filename.\n\n");
            for (Hit hit : context) {
                prompt.append("## ").append(hit.chunk().heading())
                        .append("  (").append(hit.chunk().file())
                        .append(", ").append(hit.chunk().date())
                        .append(", ").append(hit.chunk().author()).append(")\n")
                        .append(hit.chunk().text()).append("\n\n");
            }
        } else {
            // Saying so beats silence. An agent given no context and no warning will write
            // confident criteria out of nothing.
            prompt.append("\n# Knowledge base\n\nNothing was found for this stage. Treat every "
                    + "non-obvious decision as unanswerable rather than guessing.\n");
        }

        return prompt.toString();
    }

    static Result parse(String reply, String epicId, int stageIndex) {
        String where = epicId + " stage " + stageIndex;
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, where,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        List<String> unanswerable = new ArrayList<>();
        root.path("unanswerable").forEach(node -> {
            String text = node.asText("").trim();
            if (!text.isBlank()) {
                unanswerable.add(text);
            }
        });

        JsonNode criteriaNode = root.path("criteria");
        List<AcceptanceCriterion> criteria = new ArrayList<>();

        if (criteriaNode.isArray()) {
            int index = 0;
            for (JsonNode node : criteriaNode) {
                index++;
                String text = node.path("text").asText("").trim();
                if (text.isBlank()) {
                    throw new AgentReplyException(NAME, where,
                            "criterion " + index + " had no text", null);
                }

                String raw = node.path("testability").asText("");
                Testability testability;
                try {
                    testability = Testability.valueOf(raw.trim().toUpperCase().replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    // Never default this. An unreadable value becoming AUTOMATED means QA
                    // reports a pass on something it never checked.
                    throw new AgentReplyException(NAME, where,
                            "criterion %d had testability '%s'; expected AUTOMATED, OBSERVABLE, "
                                    .formatted(index, raw) + "UNREACHABLE or HUMAN_ONLY", e);
                }

                List<String> sources = new ArrayList<>();
                node.path("sources").forEach(source -> sources.add(source.asText()));

                criteria.add(new AcceptanceCriterion(
                        "AC-" + index, text, testability, List.copyOf(sources)));
            }
        }

        if (criteria.isEmpty() && unanswerable.isEmpty()) {
            // The agent produced nothing and claimed nothing was blocking it. That is not
            // a stage with no requirements; it is a reply that cannot be acted on.
            throw new AgentReplyException(NAME, where,
                    "no criteria and nothing marked unanswerable: "
                            + AgentReply.preview(cleaned), null);
        }

        return new Result(List.copyOf(criteria), List.copyOf(unanswerable));
    }

    /**
     * @param unanswerable questions for a person. While this is non-empty the stage has no
     *                     settled criteria and must not become a story.
     */
    public record Result(List<AcceptanceCriterion> criteria, List<String> unanswerable) {

        public boolean settled() {
            return unanswerable.isEmpty() && !criteria.isEmpty();
        }

        public com.loop.orchestrator.domain.Domain.TestabilitySplit split() {
            return com.loop.orchestrator.domain.Domain.TestabilitySplit.of(criteria);
        }
    }
}

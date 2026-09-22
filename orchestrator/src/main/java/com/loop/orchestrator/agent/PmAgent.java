package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps an epic as an end-to-end customer journey and marks each stage known, partial or a
 * gap. It writes no acceptance criteria — that is the BA's job, and the boundary is worth
 * enforcing: if both agents are asking the same question, one of them is out of its lane.
 *
 * <p>This runs once per epic however badly the build goes, which makes it the
 * highest-leverage place in the pipeline to spend on a strong model. A journey stage it
 * misses wastes every downstream agent's work.
 */
public class PmAgent {

    private static final Logger log = LoggerFactory.getLogger(PmAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "pm";

    private static final String SYSTEM = """
            You map a software epic as an end-to-end customer journey.

            Work from the user's side of the screen, not the system's. A stage is something
            a person experiences, not a component that runs.

            Mark each stage:
              KNOWN   - the epic or the knowledge base settles it
              PARTIAL - partly settled; something material is still open
              GAP     - nobody has decided this, and it changes what gets built

            Be strict about GAP. A gap blocks decomposition, so marking something KNOWN to
            keep things moving is how a wrong assumption reaches production. The questions
            worth raising are the ones an engineer walks straight past because the ticket
            did not mention them.

            Do not write acceptance criteria. Do not propose an implementation.

            Reply with JSON only, no prose and no code fence:
            {"stages":[{"index":1,"name":"...","description":"...","coverage":"KNOWN"}]}
            """;

    private final ChatModel model;
    private final KnowledgeIndexer knowledge;
    private final int maxOutputTokens;

    public PmAgent(ChatModel model, KnowledgeIndexer knowledge, int maxOutputTokens) {
        this.model = model;
        this.knowledge = knowledge;
        this.maxOutputTokens = maxOutputTokens;
    }

    public Journey mapJourney(Epic epic, List<Answer> answers) {
        List<Hit> context = knowledge.search(epic.title() + "\n" + epic.body(), 6);
        String prompt = buildPrompt(epic, answers, context);

        ChatModel.ChatResponse response = model.complete(
                new ChatModel.ChatRequest(NAME, SYSTEM, prompt, maxOutputTokens));

        Journey journey = parse(response.text(), epic.id());
        log.info("[pm] {} — {} of {} stages settled, {} gap(s), {} in / {} out tokens",
                epic.id(), journey.settled(), journey.stages().size(),
                journey.gaps().size(), response.inputTokens(), response.outputTokens());
        return journey;
    }

    String buildPrompt(Epic epic, List<Answer> answers, List<Hit> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Epic ").append(epic.id()).append(": ").append(epic.title())
                .append("\n\n").append(epic.body()).append("\n");

        if (!context.isEmpty()) {
            prompt.append("\n# What we already know\n\n");
            for (Hit hit : context) {
                // Dated and attributed, so a stale note can be recognised as stale rather
                // than treated as current fact.
                prompt.append("## ").append(hit.chunk().heading())
                        .append("  (").append(hit.chunk().file())
                        .append(", ").append(hit.chunk().date())
                        .append(", ").append(hit.chunk().author()).append(")\n")
                        .append(hit.chunk().text()).append("\n\n");
            }
        }

        if (!answers.isEmpty()) {
            // Answers go last and are labelled as decisions, because they outrank both the
            // epic and the knowledge base — a person settled these on purpose.
            prompt.append("\n# Decisions already made about this epic\n\n");
            for (Answer answer : answers) {
                prompt.append("- ").append(answer.answer()).append("\n");
            }
            prompt.append("\nThese are settled. Do not raise them as gaps again.\n");
        }

        return prompt.toString();
    }

    static Journey parse(String reply, String epicId) {
        String cleaned = stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, epicId,
                    "reply was not valid JSON: " + preview(cleaned), e);
        }

        JsonNode stages = root.path("stages");
        if (!stages.isArray() || stages.isEmpty()) {
            throw new AgentReplyException(NAME, epicId,
                    "reply had no stages array: " + preview(cleaned), null);
        }

        List<JourneyStage> parsed = new ArrayList<>();
        for (JsonNode stage : stages) {
            String coverage = stage.path("coverage").asText("");
            Coverage value;
            try {
                value = Coverage.valueOf(coverage.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Never default this. An unreadable coverage quietly becoming KNOWN would
                // release an epic to the architect with an open question inside it.
                throw new AgentReplyException(NAME, epicId,
                        "stage %d had coverage '%s'; expected KNOWN, PARTIAL or GAP"
                                .formatted(parsed.size() + 1, coverage), e);
            }

            parsed.add(new JourneyStage(
                    stage.path("index").asInt(parsed.size() + 1),
                    stage.path("name").asText("(unnamed)"),
                    stage.path("description").asText(""),
                    value));
        }
        return new Journey(List.copyOf(parsed));
    }

    /** Models sometimes wrap JSON in a code fence despite being asked not to. */
    private static String stripFence(String reply) {
        String trimmed = reply == null ? "" : reply.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** Carries enough context to debug from the log alone. */
    public static class AgentReplyException extends RuntimeException {
        public AgentReplyException(String agent, String epicId, String detail, Throwable cause) {
            super("[%s] unusable reply for %s — %s".formatted(agent, epicId, detail), cause);
        }
    }
}

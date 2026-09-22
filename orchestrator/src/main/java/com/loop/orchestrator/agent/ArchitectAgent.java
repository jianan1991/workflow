package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.knowledge.Knowledge.Hit;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decomposes a settled epic into stories, assigns each a complexity tier, and owns the
 * dependency graph.
 *
 * <p><b>A known weakness, deliberately left visible.</b> The dependency graph should be
 * derived mechanically from a code index — the compiler knows what imports what with
 * certainty, and paying a model to guess at it is both slower and wrong more often. No
 * code index exists yet, so this agent guesses, and {@link #validate} catches only the
 * structural mistakes (dangling references, cycles, self-dependency) rather than the
 * semantic ones. Track how often an agent discovers mid-story that it needs something from
 * a later story; that number is the cost of this shortcut.
 */
public class ArchitectAgent {

    private static final Logger log = LoggerFactory.getLogger(ArchitectAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "architect";

    private static final String SYSTEM = """
            You decompose a settled epic into implementable stories.

            Each story is one coherent change a single agent can finish. Assign a tier:
              1 - mechanical, single module, criteria are unambiguous
              2 - cross-cutting, ambiguous, or touching shared abstractions

            Be honest about tier 2. A story routed to the cheap agent that turns out to
            touch shared code fails review twice before anyone notices, which costs more
            than starting it on the strong one.

            `dependsOn` lists story ids that must be finished first, and only real ones.
            A story cannot depend on itself, and the graph cannot contain a cycle - both
            deadlock the build. When two stories are independent, say so by leaving
            dependsOn empty; a needless dependency serialises work that could run at once.

            Flag `structural: true` if the epic introduces an infrastructure dependency,
            edits shared code, migrates a schema, adds a public interface, or changes
            anything on a request hot path. That sends it to a human before code exists,
            which is the cheapest rejection point there is.

            Reply with JSON only, no prose and no code fence:
            {"structural":false,
             "stories":[{"id":"EPIC-1","title":"...","tier":1,"dependsOn":[]}]}
            """;

    private final ChatModel model;
    private final KnowledgeIndexer knowledge;
    private final int maxOutputTokens;

    public ArchitectAgent(ChatModel model, KnowledgeIndexer knowledge, int maxOutputTokens) {
        this.model = model;
        this.knowledge = knowledge;
        this.maxOutputTokens = maxOutputTokens;
    }

    public Result decompose(Epic epic, Journey journey) {
        List<Hit> context = knowledge.search(epic.title() + "\n" + epic.body(), 8);

        ChatModel.ChatResponse response = model.complete(new ChatModel.ChatRequest(
                NAME, SYSTEM, buildPrompt(epic, journey, context), maxOutputTokens));

        Result result = parse(response.text(), epic.id());
        log.info("[architect] {} — {} stories ({} tier 2), structural={}, {} in / {} out",
                epic.id(), result.stories().size(),
                result.stories().stream().filter(s -> s.tier() == 2).count(),
                result.structural(), response.inputTokens(), response.outputTokens());
        return result;
    }

    String buildPrompt(Epic epic, Journey journey, List<Hit> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Epic ").append(epic.id()).append(": ").append(epic.title())
                .append("\n\n").append(epic.body()).append("\n\n");

        prompt.append("# The customer journey, settled\n\n");
        journey.stages().forEach(stage -> prompt
                .append(stage.index()).append(". ").append(stage.name())
                .append(" [").append(stage.coverage()).append("] — ")
                .append(stage.description()).append("\n"));

        prompt.append("\nUse ").append(epic.id())
                .append("-1, -2, ... as story ids.\n");

        if (!context.isEmpty()) {
            prompt.append("\n# What the codebase and knowledge base already say\n\n");
            for (Hit hit : context) {
                prompt.append("## ").append(hit.chunk().heading())
                        .append("  (").append(hit.chunk().file()).append(")\n")
                        .append(hit.chunk().text()).append("\n\n");
            }
        }

        return prompt.toString();
    }

    static Result parse(String reply, String epicId) {
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, epicId,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        JsonNode storiesNode = root.path("stories");
        if (!storiesNode.isArray() || storiesNode.isEmpty()) {
            throw new AgentReplyException(NAME, epicId,
                    "reply had no stories: " + AgentReply.preview(cleaned), null);
        }

        List<Story> stories = new ArrayList<>();
        for (JsonNode node : storiesNode) {
            String id = node.path("id").asText("").trim();
            if (id.isBlank()) {
                throw new AgentReplyException(NAME, epicId,
                        "story " + (stories.size() + 1) + " had no id", null);
            }

            int tier = node.path("tier").asInt(0);
            if (tier != 1 && tier != 2) {
                // Defaulting to 1 would route a cross-cutting story to the cheap agent and
                // pay for two failed reviews to find out.
                throw new AgentReplyException(NAME, epicId,
                        "story %s had tier %s; expected 1 or 2".formatted(id,
                                node.path("tier").asText("(missing)")), null);
            }

            List<String> dependsOn = new ArrayList<>();
            node.path("dependsOn").forEach(dep -> dependsOn.add(dep.asText().trim()));

            stories.add(new Story(id, node.path("title").asText("(untitled)"), tier,
                    List.copyOf(dependsOn)));
        }

        validate(stories, epicId);
        return new Result(List.copyOf(stories), root.path("structural").asBoolean(false));
    }

    /**
     * Catches the graph mistakes that would deadlock the build. A dangling or circular
     * dependency does not fail loudly downstream — the fan-out simply never schedules
     * those stories, and the epic sits there looking busy.
     */
    static void validate(List<Story> stories, String epicId) {
        Set<String> ids = new LinkedHashSet<>();
        for (Story story : stories) {
            if (!ids.add(story.id())) {
                throw new AgentReplyException(NAME, epicId,
                        "duplicate story id " + story.id(), null);
            }
        }

        for (Story story : stories) {
            for (String dependency : story.dependsOn()) {
                if (dependency.equals(story.id())) {
                    throw new AgentReplyException(NAME, epicId,
                            story.id() + " depends on itself", null);
                }
                if (!ids.contains(dependency)) {
                    throw new AgentReplyException(NAME, epicId,
                            "%s depends on %s, which is not in this epic"
                                    .formatted(story.id(), dependency), null);
                }
            }
        }

        detectCycle(stories, epicId);
    }

    private static void detectCycle(List<Story> stories, String epicId) {
        // Repeatedly peel off everything whose dependencies are already placed. Whatever
        // will not peel is in a cycle — the same layering the epic workflow uses to fan out.
        Set<String> placed = new LinkedHashSet<>();
        List<Story> remaining = new ArrayList<>(stories);

        while (!remaining.isEmpty()) {
            List<Story> ready = remaining.stream()
                    .filter(story -> placed.containsAll(story.dependsOn()))
                    .toList();

            if (ready.isEmpty()) {
                throw new AgentReplyException(NAME, epicId,
                        "dependency cycle among " + remaining.stream().map(Story::id).toList(),
                        null);
            }
            ready.forEach(story -> placed.add(story.id()));
            remaining.removeAll(ready);
        }
    }

    /**
     * @param structural true sends the epic to the architecture gate before any code
     *                   exists. Expect it on roughly a quarter of epics — a gate that
     *                   fires on every one gets rubber-stamped within a fortnight.
     */
    public record Result(List<Story> stories, boolean structural) {
    }
}

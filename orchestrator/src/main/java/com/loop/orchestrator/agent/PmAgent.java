package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.knowledge.Knowledge.SearchOutcome;
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

    private static final String QUESTION_SYSTEM = """
            A stage of a customer journey has a gap - nobody has decided it, and it changes
            what gets built. Your job is to get it settled.

            Write the question you would ask a person. One question, answerable in a
            sentence or two. Say what each possible answer would change, because that is
            what makes a busy person answer it now rather than later.

            Also give up to three different phrasings that might find the answer already
            written down somewhere. Vary the vocabulary - the note you are looking for was
            written by someone who used different words than the ticket does.

            Name the knowledge-base file the answer should be committed to, so it is
            settled once rather than asked again next quarter.

            Reply with JSON only, no prose and no code fence:
            {"question":"...","searchQueries":["...","...","..."],"targetKbFile":"kb/x.md"}
            """;

    /**
     * Turns a gap into a question for a person - but searches first.
     *
     * <p>The agent proposes its own phrasings and they are run against the knowledge base
     * before anybody is interrupted. Two things come out of that: sometimes the answer is
     * already written down, and when it is not, the queries that failed are recorded on the
     * question. "Three of three attempts used, no usable hit" is what separates a real gap
     * from an agent that gave up after one try, and it is the only honest read on whether
     * retrieval is working.
     */
    public Question raiseQuestion(String questionId, Epic epic, JourneyStage gap) {
        ChatModel.ChatResponse response = model.complete(new ChatModel.ChatRequest(
                NAME, QUESTION_SYSTEM, buildQuestionPrompt(epic, gap), maxOutputTokens));

        Drafted drafted = parseQuestion(response.text(), epic.id(), gap.index());
        SearchOutcome outcome = knowledge.searchUntilSufficient(drafted.searchQueries(), 5);

        log.info("[pm] {} stage {} — {} of {} searches used, {}",
                epic.id(), gap.index(), outcome.queriesTried().size(), outcome.attemptsAllowed(),
                outcome.sufficient() ? "found something; asking anyway" : "nothing found");

        return new Question(questionId, NAME, drafted.question(),
                outcome.queriesTried(), outcome.attemptsAllowed(),
                drafted.targetKbFile(), gap.index());
    }

    String buildQuestionPrompt(Epic epic, JourneyStage gap) {
        return """
                # Epic %s: %s

                %s

                # The stage nobody has decided

                Stage %d: %s
                %s
                """.formatted(epic.id(), epic.title(), epic.body(),
                gap.index(), gap.name(), gap.description());
    }

    static Drafted parseQuestion(String reply, String epicId, int stageIndex) {
        String where = epicId + " stage " + stageIndex;
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, where,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        String question = root.path("question").asText("").trim();
        if (question.isBlank()) {
            // Blocking an epic without saying what would unblock it wastes the one
            // interruption you get.
            throw new AgentReplyException(NAME, where,
                    "no question - an epic cannot be blocked on nothing", null);
        }

        List<String> queries = new ArrayList<>();
        root.path("searchQueries").forEach(node -> {
            String query = node.asText("").trim();
            if (!query.isBlank()) {
                queries.add(query);
            }
        });
        if (queries.isEmpty()) {
            // Without attempted searches the question carries no evidence the gap is real,
            // and the queue cannot tell a genuine gap from a lazy agent.
            throw new AgentReplyException(NAME, where,
                    "no search queries - a question with nothing tried behind it is "
                            + "indistinguishable from not looking", null);
        }

        String targetFile = root.path("targetKbFile").asText("").trim();
        return new Drafted(question, List.copyOf(queries),
                targetFile.isBlank() ? "kb/decisions.md" : targetFile);
    }

    /** What the model drafted, before the searches are actually run. */
    record Drafted(String question, List<String> searchQueries, String targetKbFile) {
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
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, epicId,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        JsonNode stages = root.path("stages");
        if (!stages.isArray() || stages.isEmpty()) {
            throw new AgentReplyException(NAME, epicId,
                    "reply had no stages array: " + AgentReply.preview(cleaned), null);
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

}

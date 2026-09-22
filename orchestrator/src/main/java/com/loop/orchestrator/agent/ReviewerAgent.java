package com.loop.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.llm.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Reads the diff and answers one question: does this code do what the ticket said?
 *
 * <p>Static, fast and cheap. It is not QA — it never runs anything — and the gap between
 * them is deliberate. A reviewer reading a diff will miss "the 429 fires correctly but
 * returns an empty body", because the code plainly looks right. Only something that called
 * the endpoint and looked at the response catches that.
 *
 * <p><b>This is the structurally weakest component in the pipeline.</b> A model tends to
 * bless work from its own family, so it runs on a different tier from whichever agent
 * authored, scope creep alone blocks auto-approval, and every auto-approval is counted so
 * the revert rate can be read rather than guessed at.
 */
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "reviewer";

    private static final String SYSTEM = """
            You review a pull request against the ticket that asked for it.

            Answer two things.

            matchesCriteria - does the diff actually satisfy every acceptance criterion?
            Not "is this reasonable code" and not "would I have written it this way".
            Code that is well written and does something other than what was asked does
            not match.

            scopeExceeded - does the diff change anything the story did not authorise?
            Configuration files, shared modules, unrelated cleanups, dependency bumps. Be
            strict. A change can be both correct and out of scope, and saying so is the
            point: it blocks automatic merge and sends it to a person, which is cheap.
            Missing it means an unreviewed change to shared config ships silently.

            In `verdict`, say what a reviewer should look hardest at. Name the specific
            risk, not a summary of the diff - the person reading this already has the diff.

            Reply with JSON only, no prose and no code fence:
            {"matchesCriteria":true,"scopeExceeded":false,"verdict":"...",
             "unmetCriteria":["AC-2"],"outOfScopeFiles":["application.yml"]}
            """;

    private final ChatModel model;
    private final int maxOutputTokens;

    public ReviewerAgent(ChatModel model, int maxOutputTokens) {
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    public Result review(Story story, List<AcceptanceCriterion> criteria,
                         Agent author, String diff, List<String> changedFiles) {

        ChatModel.ChatResponse response = model.complete(new ChatModel.ChatRequest(
                NAME, SYSTEM, buildPrompt(story, criteria, author, diff, changedFiles),
                maxOutputTokens));

        Result result = parse(response.text(), story.id());
        log.info("[reviewer] {} (author {}) — matches={} scope={} — {} in / {} out",
                story.id(), author, result.review().matchesCriteria(),
                result.review().scopeExceeded(), response.inputTokens(), response.outputTokens());
        return result;
    }

    String buildPrompt(Story story, List<AcceptanceCriterion> criteria,
                       Agent author, String diff, List<String> changedFiles) {

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Story ").append(story.id()).append(": ").append(story.title())
                .append("\n\nTier ").append(story.tier())
                // The author is named so scope creep can be judged against who was asked to
                // do what — not so the reviewer can defer to a stronger agent's judgement.
                .append(", implemented by the ").append(author).append(" agent.\n");

        prompt.append("\n# Acceptance criteria\n\n");
        if (criteria.isEmpty()) {
            prompt.append("None were recorded. Treat that as a failure to match: there is "
                    + "nothing to check this diff against.\n");
        } else {
            criteria.forEach(criterion -> prompt
                    .append("- ").append(criterion.id()).append(": ")
                    .append(criterion.text())
                    .append("  [").append(criterion.testability()).append("]\n"));
        }

        prompt.append("\n# Files changed\n\n");
        changedFiles.forEach(file -> prompt.append("- ").append(file).append("\n"));

        prompt.append("\n# Diff\n\n").append(diff).append("\n");
        return prompt.toString();
    }

    static Result parse(String reply, String storyId) {
        String cleaned = AgentReply.stripFence(reply);

        JsonNode root;
        try {
            root = JSON.readTree(cleaned);
        } catch (JsonProcessingException e) {
            throw new AgentReplyException(NAME, storyId,
                    "reply was not valid JSON: " + AgentReply.preview(cleaned), e);
        }

        // Both flags must be stated. A missing boolean quietly reading as false would
        // approve a diff nobody checked and clear it for automatic merge.
        requireBoolean(root, "matchesCriteria", storyId);
        requireBoolean(root, "scopeExceeded", storyId);

        String verdict = root.path("verdict").asText("").trim();
        if (verdict.isBlank()) {
            throw new AgentReplyException(NAME, storyId,
                    "no verdict — a person needs to be told what to look at", null);
        }

        List<String> unmet = new java.util.ArrayList<>();
        root.path("unmetCriteria").forEach(node -> unmet.add(node.asText()));

        List<String> outOfScope = new java.util.ArrayList<>();
        root.path("outOfScopeFiles").forEach(node -> outOfScope.add(node.asText()));

        boolean matches = root.path("matchesCriteria").asBoolean();
        boolean scopeExceeded = root.path("scopeExceeded").asBoolean();

        // A reply that says everything matched while listing criteria that did not is
        // contradicting itself. Taking the optimistic half would merge it.
        if (matches && !unmet.isEmpty()) {
            throw new AgentReplyException(NAME, storyId,
                    "claims the criteria are met but lists " + unmet + " as unmet", null);
        }
        if (!scopeExceeded && !outOfScope.isEmpty()) {
            throw new AgentReplyException(NAME, storyId,
                    "claims scope was respected but lists " + outOfScope + " as out of scope",
                    null);
        }

        return new Result(new ReviewResult(matches, scopeExceeded, verdict),
                List.copyOf(unmet), List.copyOf(outOfScope));
    }

    private static void requireBoolean(JsonNode root, String field, String storyId) {
        JsonNode node = root.path(field);
        if (!node.isBoolean()) {
            throw new AgentReplyException(NAME, storyId,
                    "'%s' was %s; a review must state it outright".formatted(field,
                            node.isMissingNode() ? "missing" : "'" + node.asText() + "'"), null);
        }
    }

    public record Result(ReviewResult review, List<String> unmetCriteria,
                         List<String> outOfScopeFiles) {
    }
}

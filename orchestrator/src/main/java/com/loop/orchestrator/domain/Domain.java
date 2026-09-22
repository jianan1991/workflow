package com.loop.orchestrator.domain;

import java.util.List;
import java.util.Map;

/**
 * Every shape that crosses a workflow or activity boundary.
 *
 * <p>These are records so Temporal's Jackson data converter can round-trip them without
 * configuration, and so a replayed workflow deserialises exactly what it serialised.
 *
 * <p>Kept in one file on purpose: it is the contract between the workflow and everything
 * else, and it should be readable in one sitting.
 */
public final class Domain {

    private Domain() {
    }

    // ------------------------------------------------------------------ input

    public record Epic(String id, String title, String body) {
    }

    // ---------------------------------------------------------------- journey

    /**
     * How well the PM agent understands one stage of the customer journey.
     * The architect will not decompose an epic that still has a GAP.
     */
    public enum Coverage {KNOWN, PARTIAL, GAP}

    public record JourneyStage(int index, String name, String description, Coverage coverage) {
    }

    public record Journey(List<JourneyStage> stages) {

        public boolean holdsTogether() {
            return stages.stream().noneMatch(s -> s.coverage() == Coverage.GAP);
        }

        public List<JourneyStage> gaps() {
            return stages.stream().filter(s -> s.coverage() == Coverage.GAP).toList();
        }

        public long settled() {
            return stages.stream().filter(s -> s.coverage() == Coverage.KNOWN).count();
        }
    }

    // --------------------------------------------------------------- blocking

    /**
     * @param searchesAttempted what the agent already looked for. Showing this is what
     *                          separates a real gap in the knowledge base from an agent
     *                          that gave up early — and it is the only honest signal of
     *                          whether retrieval is any good.
     */
    public record Question(String id,
                           String askedBy,
                           String text,
                           List<String> searchesAttempted,
                           int attemptsAllowed,
                           String targetKbFile,
                           Integer journeyStage) {
    }

    public record Answer(String questionId, String answer) {
    }

    // ---------------------------------------------------------------- stories

    /**
     * Tier 1 is mechanical, single-module, clear criteria — routed to the cheap agent.
     * Tier 2 is cross-cutting, ambiguous, or touching shared abstractions.
     */
    public record Story(String id, String title, int tier, List<String> dependsOn) {
    }

    // --------------------------------------------------------------- estimate

    /**
     * Produced at gate 1, before any code exists.
     *
     * <pre>estimate = Σ (stage runs × median cost per run) × rework multiplier</pre>
     *
     * @param humanMinutes the number that actually decides whether this pipeline saves
     *                     anything. At a loaded engineer rate these minutes cost roughly
     *                     eight times the model spend — forecasting only dollars
     *                     forecasts the wrong number.
     */
    public record Estimate(double dollars,
                           int humanMinutes,
                           double reworkMultiplier,
                           Map<String, Double> byStage) {
    }

    // ------------------------------------------------------------------ gates

    public enum Gate {EPIC_PLAN, ARCHITECTURE, PULL_REQUEST}

    public record GateDecision(Gate gate, boolean approved, String note) {
    }

    // ------------------------------------------------------------ build stage

    /**
     * Tier 1 goes to MID, tier 2 to SENIOR. A MID story that fails review twice is
     * reassigned to SENIOR rather than escalated straight to a person — only a SENIOR
     * failure reaches a human, as a blocked ticket.
     */
    public enum Agent {MID, SENIOR}

    public record CiResult(boolean passed, double diffCoverage, double mutationScore, String note) {
    }

    /**
     * The reviewer reads the diff. Static, fast, cheap. It answers one question: does this
     * code do what the ticket said?
     *
     * <p>It is structurally the weakest component here — a model tends to bless work from
     * its own family — which is why it runs on a different model family from the author
     * and why {@code scopeExceeded} blocks auto-approval on its own.
     */
    public record ReviewResult(boolean matchesCriteria, boolean scopeExceeded, String verdict) {
    }

    /**
     * Where a QA finding goes is the whole design in one field.
     *
     * <ul>
     *   <li>{@code CODE_DEFECT} — back to the dev agent, not to a person. This is the
     *       automated rework loop and it is where the leverage is.
     *   <li>{@code CRITERION_WRONG} — the code met the criteria and the criteria were
     *       wrong. Sending this to a developer produces a second wrong implementation.
     *   <li>{@code UNVERIFIABLE} — reclassify the criterion; do not retry the code.
     * </ul>
     */
    public enum QaOutcome {PASS, CODE_DEFECT, CRITERION_WRONG, UNVERIFIABLE}

    public record QaResult(QaOutcome outcome,
                           String evidence,
                           int unverifiableCount,
                           double costUsd,
                           int durationSeconds) {
    }

    public enum StoryState {
        QUEUED, IMPLEMENTING, CI, REVIEW, QA,
        AWAITING_PR_REVIEW, MERGED, BLOCKED, RETURNED_TO_PM
    }

    /**
     * @param reworkLoops passes through QA → dev that never touched a person. Every one of
     *                    these is the system paying for itself; count them.
     */
    public record StoryStatus(String storyId,
                              int tier,
                              Agent agent,
                              StoryState state,
                              int devAttempts,
                              int qaRuns,
                              int reworkLoops,
                              boolean autoApproved,
                              double costUsd,
                              String note) {
    }

    // ----------------------------------------------------------------- status

    public enum Phase {
        JOURNEY_MAPPING,
        AWAITING_ANSWER,
        DECOMPOSING,
        ESTIMATING,
        EPIC_REVIEW,
        BUILDING,
        MERGED,
        ABANDONED
    }

    /**
     * What a query against a running workflow returns. This is the shape the board and
     * epic-detail screens read.
     */
    public record EpicStatus(String epicId,
                             Phase phase,
                             Journey journey,
                             Question openQuestion,
                             List<Story> stories,
                             List<StoryStatus> storyStatuses,
                             Estimate estimate,
                             int questionsAsked,
                             String note) {
    }
}

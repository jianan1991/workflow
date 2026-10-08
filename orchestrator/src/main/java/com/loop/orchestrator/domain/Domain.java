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

    // ------------------------------------------------------ acceptance criteria

    /**
     * How a criterion can be verified — decided when it is <b>written</b>, not when QA
     * runs. Classifying late means discovering your exposure at the end of an epic instead
     * of at the gate before any code exists.
     */
    public enum Testability {
        /** QA boots the app, calls it, asserts. "429 returned after 100 requests in 60s." */
        AUTOMATED,
        /** QA can capture the output but not judge it. "The error message is clear." */
        OBSERVABLE,
        /** Needs a third-party sandbox, production data volumes, or a browser. */
        UNREACHABLE,
        /** Judgement. Wording, feel, whether a default value is sensible. */
        HUMAN_ONLY
    }

    /**
     * @param kbSources knowledge-base files this rests on. A non-obvious decision with no
     *                  citation is the signal to ask a person rather than to invent one —
     *                  it is the closest thing this pipeline has to an agent knowing that
     *                  it does not know.
     */
    public record AcceptanceCriterion(String id,
                                      String text,
                                      Testability testability,
                                      List<String> kbSources) {
    }

    /** What the gate-1 screen shows: your manual exposure, before anything is built. */
    public record TestabilitySplit(int automated, int observable, int unreachable, int humanOnly) {

        public static TestabilitySplit of(List<AcceptanceCriterion> criteria) {
            int automated = 0;
            int observable = 0;
            int unreachable = 0;
            int humanOnly = 0;
            for (AcceptanceCriterion criterion : criteria) {
                switch (criterion.testability()) {
                    case AUTOMATED -> automated++;
                    case OBSERVABLE -> observable++;
                    case UNREACHABLE -> unreachable++;
                    case HUMAN_ONLY -> humanOnly++;
                }
            }
            return new TestabilitySplit(automated, observable, unreachable, humanOnly);
        }

        public int total() {
            return automated + observable + unreachable + humanOnly;
        }

        /**
         * The metric to watch. If it stops climbing, criteria are being written too
         * vaguely — a BA problem, not a QA one.
         */
        public double automatedShare() {
            return total() == 0 ? 0.0 : (double) automated / total();
        }
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

    /**
     * @param measured whether the build actually ran and produced these numbers. Stated
     *                 explicitly and with no convenient default, because the alternative is
     *                 what bug 3 was: a stub returning {@code passed=true} with zeroed
     *                 coverage, and auto-approval trusting it. Each half was honest; together
     *                 they merged work with no quality gate at all.
     */
    public record CiResult(boolean measured, boolean passed, double diffCoverage,
                           double mutationScore, String note) {

        /** Nothing ran. Cannot pass, because there is nothing to have passed. */
        public static CiResult notRun(String why) {
            return new CiResult(false, false, 0.0, 0.0, why);
        }

        /**
         * Whether this result carries enough to approve a merge on with nobody watching.
         *
         * <p>Two separate claims, and conflating them is how a story merged unchecked:
         * {@code measured} says a build ran at all, {@code passed} says what it concluded.
         * An unmeasured result is not a lenient pass, it is no information.
         *
         * <p>A zero mutation score on a result that claims to have been measured is the
         * second case. Mutation is the gate an agent cannot talk its way past, so zero
         * means the reporting is broken rather than that the code is perfect.
         */
        public boolean trustworthy() {
            return measured && mutationScore > 0.0;
        }
    }

    /**
     * The reviewer reads the diff. Static, fast, cheap. It answers one question: does this
     * code do what the ticket said?
     *
     * <p>It is structurally the weakest component here — a model tends to bless work from
     * its own family — which is why it runs on a different model family from the author
     * and why {@code scopeExceeded} blocks auto-approval on its own.
     */
    public record ReviewResult(boolean matchesCriteria, boolean scopeExceeded, String verdict,
                               double costUsd) {

        public ReviewResult(boolean matchesCriteria, boolean scopeExceeded, String verdict) {
            this(matchesCriteria, scopeExceeded, verdict, 0.0);
        }
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

    /**
     * The pull request a person is asked to look at.
     *
     * <p>{@code opened} is separate from a non-null url on purpose. "No pull request was
     * opened and here is why" is a state the workflow has to carry to a human, and an
     * empty string would read as one that opened without a link.
     */
    public record PullRequest(int number, String url, boolean opened, String note) {

        public static PullRequest notOpened(String why) {
            return new PullRequest(0, "", false, why);
        }
    }

    public record QaResult(QaOutcome outcome,
                           String evidence,
                           int unverifiableCount,
                           double costUsd,
                           int durationSeconds) {
    }

    /**
     * What QA saw when it actually exercised the system — a real HTTP response, a log
     * line, a database row. Gathered by running the software, not by asking a model.
     *
     * <p>The split matters: booting the app and calling it is deterministic work that
     * belongs in an activity, and judging the result against a criterion is what a model
     * is for. Conflating them produces an agent that reports on tests it never ran.
     */
    public record Observation(String what, String evidence) {
    }

    /** One criterion, judged. */
    public record CriterionVerdict(String criterionId, QaOutcome outcome, String evidence) {
    }

    public enum StoryState {
        QUEUED, IMPLEMENTING, CI, REVIEW, QA,
        AWAITING_PR_REVIEW, MERGED, BLOCKED, RETURNED_TO_PM
    }

    /**
     * @param reworkLoops passes through QA → dev that never touched a person. Every one of
     *                    these is the system paying for itself; count them.
     */
    /**
     * What a dev agent produced, and what it spent doing it.
     *
     * <p>Cost is reported per activity rather than accumulated somewhere central, because
     * an activity is the only thing that knows what its own call consumed — and a workflow
     * summing values it was handed is replay-safe, while one reading a running total is not.
     */
    /**
     * @param retryable whether another attempt could plausibly succeed. An agent that tried
     *                  and fell short is retryable - escalating it to a stronger model is
     *                  exactly the right move. A missing workspace or an executable that is
     *                  not installed is not: no model fixes a directory that does not exist,
     *                  and burning four escalation rounds to discover that wastes money and
     *                  hides the real problem.
     */
    public record ImplementResult(boolean succeeded,
                                  String branch,
                                  double costUsd,
                                  String failure,
                                  boolean retryable) {

        public static ImplementResult built(String branch, double costUsd) {
            return new ImplementResult(true, branch, costUsd, null, true);
        }

        /** The agent tried and fell short. Another attempt, or a stronger agent, may do it. */
        public static ImplementResult failed(String failure, double costUsd) {
            return new ImplementResult(false, "", costUsd, failure, true);
        }

        /** The harness could not run at all. A person has to fix this, not another attempt. */
        public static ImplementResult broken(String failure) {
            return new ImplementResult(false, "", 0.0, failure, false);
        }
    }

    public record StoryStatus(String storyId,
                              int tier,
                              Agent agent,
                              StoryState state,
                              int devAttempts,
                              int qaRuns,
                              int reworkLoops,
                              boolean autoApproved,
                              double costUsd,
                              String pullRequestUrl,
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
                             TestabilitySplit testability,
                             int questionsAsked,
                             String note) {
    }
}

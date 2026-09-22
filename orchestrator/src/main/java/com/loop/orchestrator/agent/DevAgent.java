package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Story;

import java.util.List;

/**
 * The one agent that is not a single model call.
 *
 * <p>Implementing a story is a multi-turn loop — read the code, edit, run the tests, read
 * the failure, edit again — and that loop belongs to a coding harness, not to this process.
 * Which is why the dev agent sits behind its own interface rather than behind
 * {@code ChatModel} like the other five: the seam is a whole agent, not a completion.
 */
public interface DevAgent {

    /**
     * @param previousFailure what went wrong last time, or null on the first attempt. Passed
     *                        through so attempt two is a correction rather than a blind
     *                        retry — the single cheapest thing that makes a rework loop
     *                        converge instead of repeating itself.
     */
    Result implement(Story story,
                     List<AcceptanceCriterion> criteria,
                     Agent agent,
                     int attempt,
                     String previousFailure);

    String describe();

    /**
     * @param succeeded   whether the harness believes it finished the task. Not whether the
     *                    code is correct — that is what CI, the reviewer and QA are for, and
     *                    an agent's own opinion of its work is the least reliable signal
     *                    available.
     * @param costUsd     what the run consumed, when the harness reports it. Zero means
     *                    unknown as often as it means free, so it is logged either way.
     */
    record Result(boolean succeeded,
                  String summary,
                  String branch,
                  double costUsd,
                  int inputTokens,
                  int outputTokens,
                  String failure) {

        public static Result failed(String failure) {
            return new Result(false, "", "", 0.0, 0, 0, failure);
        }
    }

    /** Builds the task text handed to the harness. Shared so both implementations agree. */
    static String taskFor(Story story, List<AcceptanceCriterion> criteria,
                          int attempt, String previousFailure) {

        StringBuilder task = new StringBuilder();
        task.append("Implement story ").append(story.id()).append(": ")
                .append(story.title()).append("\n\n");

        task.append("It is done when all of these hold:\n\n");
        criteria.forEach(criterion -> task
                .append("- ").append(criterion.id()).append(": ")
                .append(criterion.text()).append("\n"));

        if (previousFailure != null && !previousFailure.isBlank()) {
            // Naming the specific failure is what separates a correction from a retry. An
            // agent told only "try again" tends to produce the same thing more confidently.
            task.append("\nThis is attempt ").append(attempt)
                    .append(". The previous attempt failed with:\n\n")
                    .append(previousFailure)
                    .append("\n\nFix that specifically. Do not rewrite work that was already "
                            + "correct.\n");
        }

        task.append("\nWrite tests that assert behaviour, not shape. A test that calls a "
                + "method and checks nothing will pass coverage and fail the mutation gate, "
                + "which blocks the pull request.\n");

        task.append("\nStay inside what this story asked for. Editing shared configuration "
                + "or unrelated modules is flagged as out of scope and sends the change to a "
                + "person, even when it is correct.\n");

        return task.toString();
    }
}

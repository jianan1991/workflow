package com.loop.orchestrator.activity;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.Story;
import io.temporal.activity.ActivityInterface;

import java.util.List;

/**
 * Everything non-deterministic the epic workflow needs.
 *
 * <p><b>This boundary is the design.</b> Workflow code is replayed from history on every
 * recovery, so every LLM call, every Qdrant or code-index query, and every GitHub or CI
 * API call has to live behind an activity. Put one of them in the workflow method and
 * replays will produce garbage that is very hard to debug.
 *
 * <p>It is also why an agent-graph framework has no place inside a workflow: once the LLM
 * call is in an activity, the graph disappears, because Temporal is the graph.
 */
@ActivityInterface
public interface PlanningActivities {

    /**
     * PM agent. Maps the epic as an end-to-end customer journey and marks each stage
     * known, partial or a gap.
     *
     * @param answers everything a human has already told us about this epic, so a re-run
     *                after an answer does not ask the same question twice
     */
    Journey mapJourney(Epic epic, List<Answer> answers);

    /**
     * Put a question in front of a person, with the searches the agent already tried.
     */
    Question raiseQuestion(String epicId, String askedBy, JourneyStage gap);

    /**
     * Commit a human answer to the knowledge base as a pull request.
     *
     * <p>Only durable decisions go here. Transient status — what is done, pending or
     * blocked — belongs on the board and in Git, and writing it into the knowledge base
     * pollutes retrieval: an agent asking "how does audit export work?" starts getting
     * back "LOOP-09-2 is pending" instead of the retention policy.
     */
    void recordAnswer(String epicId, Question question, Answer answer);

    /**
     * Architect. Decomposes the epic into stories, assigns a complexity tier, and owns
     * the dependency graph.
     *
     * <p>Derive the dependency graph mechanically from the code index first — javac
     * already knows what imports what with certainty, and paying a model to guess at it
     * is both slower and wrong more often.
     */
    List<Story> decompose(Epic epic, Journey journey);

    /**
     * BA agent. Turns a story into testable acceptance criteria.
     *
     * <p>Everything downstream is judged against these. Without them the reviewer is asked
     * whether a diff satisfies requirements nobody wrote, and QA reports on criteria it
     * invented - both produce confident output that means nothing.
     *
     * @return empty when the agent could not settle the story. That is the signal to raise
     *         a question, not to proceed with a guess.
     */
    List<AcceptanceCriterion> writeCriteria(Epic epic, Story story, Journey journey);

    /**
     * Cost and human-time forecast, produced before any code exists so gate 1 can see it.
     */
    Estimate estimate(String epicId, List<Story> stories, double reworkMultiplier);

    /**
     * Push phase changes to the board. Status lives there, never in the knowledge base.
     */
    void publishStatus(String epicId, Phase phase, String note);
}

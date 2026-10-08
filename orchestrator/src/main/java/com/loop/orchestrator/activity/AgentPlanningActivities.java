package com.loop.orchestrator.activity;

import com.loop.orchestrator.agent.ArchitectAgent;
import com.loop.orchestrator.agent.BaAgent;
import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.cost.EstimateCalculator;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The planning half of the pipeline, driven by real agents.
 *
 * <p>Whether a model is actually reached is decided one layer down, at
 * {@code ChatModel} — scripted by default. Everything here is the same code either way,
 * which is the point: the path that runs in a test is the path that runs in production.
 *
 * <p>Note what this class is <em>not</em>: it does no reasoning of its own. It sequences
 * agent calls, keeps the parts Temporal needs deterministic out of them, and turns what
 * comes back into the shapes the workflow expects. Anything resembling judgement belongs
 * in an agent, where it can be prompted, tested and priced.
 */
@Component
public class AgentPlanningActivities implements PlanningActivities {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanningActivities.class);

    private final PmAgent pm;
    private final BaAgent ba;
    private final ArchitectAgent architect;
    private final AgentCostModel costs;

    public AgentPlanningActivities(PmAgent pm, BaAgent ba, ArchitectAgent architect,
                                   AgentCostModel costs) {
        this.pm = pm;
        this.ba = ba;
        this.architect = architect;
        this.costs = costs;
    }

    @Override
    public Journey mapJourney(Epic epic, List<Answer> answers) {
        return pm.mapJourney(epic, answers);
    }

    @Override
    public Question raiseQuestion(String questionId, Epic epic, String askedBy,
                                  JourneyStage gap) {
        // The id comes from the workflow. It used to come from a counter on this instance,
        // which reset whenever the worker restarted - and since the workflow keys answers
        // by id, a reused id let an answer to one question satisfy a different one and
        // release the epic on a reply to something else.
        return pm.raiseQuestion(questionId, epic, gap);
    }

    @Override
    public void recordAnswer(String epicId, Question question, Answer answer) {
        // Still not writing anything. Opening a knowledge-base PR needs the repo wired,
        // and a half-built version that logs a success it did not achieve is worse than
        // one that plainly says so.
        log.info("[kb] {} answered on {} -> would commit to {} (not yet wired)",
                question.id(), epicId, question.targetKbFile());
    }

    @Override
    public List<Story> decompose(Epic epic, Journey journey) {
        ArchitectAgent.Result result = architect.decompose(epic, journey);

        if (result.structural()) {
            // Recorded, not acted on. Gate 2 is phase 4 of the plan and the workflow has
            // no branch for it yet; saying so is better than dropping it silently.
            log.warn("[architect] {} is a structural change — gate 2 should fire, and the "
                    + "workflow does not implement it yet", epic.id());
        }
        return result.stories();
    }

    @Override
    public Estimate estimate(String epicId, List<Story> stories, double reworkMultiplier) {
        Estimate estimate = new EstimateCalculator(costs.unitCosts(), reworkMultiplier)
                .estimate(stories, hasUserInterface(stories));

        log.info("[estimate] {} -> ${} and {} human minutes across {} stories",
                epicId, estimate.dollars(), estimate.humanMinutes(), stories.size());
        return estimate;
    }

    @Override
    public void publishStatus(String epicId, Phase phase, String note) {
        log.info("[board] {} -> {} ({})", epicId, phase, note);
    }

    @Override
    public List<AcceptanceCriterion> writeCriteria(Epic epic, Story story, Journey journey) {
        BaAgent.Result result = ba.writeCriteria(epic, story, journey);

        if (!result.settled()) {
            // Returning the partial criteria would be worse than returning none: the story
            // would build and be judged against half a specification, and pass.
            log.warn("[ba] {} not settled — {} open question(s): {}",
                    story.id(), result.unanswerable().size(), result.unanswerable());
            return List.of();
        }
        return result.criteria();
    }

    /**
     * Crude on purpose, and only used to price the designer. A real answer comes from the
     * PM journey containing a stage a user interacts with directly; this is a placeholder
     * that is at least honest about being one.
     */
    private static boolean hasUserInterface(List<Story> stories) {
        return stories.stream().anyMatch(story -> {
            String title = story.title().toLowerCase();
            return title.contains("screen") || title.contains("page") || title.contains("ui");
        });
    }
}

package com.loop.orchestrator.activity;

import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.cost.EstimateCalculator;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Coverage;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.Estimate;
import com.loop.orchestrator.domain.Domain.Journey;
import com.loop.orchestrator.domain.Domain.JourneyStage;
import com.loop.orchestrator.domain.Domain.Phase;
import com.loop.orchestrator.domain.Domain.Question;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.Testability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Stub agents. No model is called and nothing is written anywhere.
 *
 * <p>Not a Spring bean. The running system uses the agent-backed activities; this
 * exists so orchestration can be tested without any agent in the picture at all —
 * which is the only way to tell an orchestration bug from an agent one.
 *
 * <p>This exists so the orchestration can be proved on its own — start an epic, watch the
 * workflow block on a human for as long as it takes, kill the worker, restart it, and see
 * the workflow resume where it was. If that does not work, nothing built on top of it will.
 *
 * <p>Replace one method at a time with a real agent. The PM agent goes first, journey
 * mapping only, and its gap questions get validated against ones a person would actually
 * have asked before any story is generated.
 */
public class StubPlanningActivities implements PlanningActivities {

    private static final Logger log = LoggerFactory.getLogger(StubPlanningActivities.class);

    /**
     * Unit costs come from cost-model.properties, the same file the phase 1 worksheet
     * reads. Carrying a second copy here would guarantee the two drift apart, and the
     * estimate quoted at gate 1 would stop matching the one anybody priced by hand.
     */
    private final AgentCostModel costs;

    /**
     * Opt-in marker for an epic whose journey has two gaps rather than one, so a test can
     * drive two rounds of questions. Opt-in because every other test wants the
     * one-question path and would otherwise have to answer twice.
     */
    public static final String SECOND_GAP_MARKER = "[two-gaps]";

    public StubPlanningActivities(@Value("${cost.model-file:../cost-model.properties}") String file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(file))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the cost model at " + file, e);
        }
        this.costs = new AgentCostModel(properties);
    }

    @Override
    public Journey mapJourney(Epic epic, List<Answer> answers) {
        log.info("[pm] mapping journey for {} with {} prior answer(s)", epic.id(), answers.size());

        // The gap closes once a human has answered anything. A real PM agent would
        // re-read the answers and re-derive coverage; this is enough to exercise the
        // block-and-resume path end to end.
        Coverage stage5 = answers.isEmpty() ? Coverage.GAP : Coverage.KNOWN;

        // A second gap, only for an epic that asked for one. It needs two answers, which
        // is what makes a second question — and so a second question id — happen.
        Coverage stage4 = epic.body().contains(SECOND_GAP_MARKER) && answers.size() < 2
                ? Coverage.GAP
                : Coverage.PARTIAL;

        return new Journey(List.of(
                new JourneyStage(1, "Integrate",
                        "A developer wires up the API with a key they generated themselves.",
                        Coverage.KNOWN),
                new JourneyStage(2, "Grow",
                        "Their traffic climbs month on month toward the plan ceiling.",
                        Coverage.KNOWN),
                new JourneyStage(3, "Warning",
                        "Nothing states whether they are told before they hit the wall.",
                        Coverage.PARTIAL),
                new JourneyStage(4, "Blocked",
                        "Requests start returning 429. What the body carries is half answered.",
                        stage4),
                new JourneyStage(5, "Ask for more",
                        "They want a higher ceiling. By which route, and who grants it?",
                        stage5),
                new JourneyStage(6, "Resolved",
                        "The new ceiling applies without a redeploy or a restart.",
                        Coverage.KNOWN)));
    }

    @Override
    public Question raiseQuestion(String questionId, Epic epic, String askedBy,
                                  JourneyStage gap) {
        log.info("[{}] blocked on {} — stage {} is a gap", askedBy, epic.id(), gap.index());

        return new Question(
                questionId,
                askedBy,
                "Who grants a higher limit, and how quickly? If the answer is self-serve, "
                        + "this epic gains an admin surface and roughly doubles.",
                List.of("rate limit increase", "plan ceiling approval", "throttling policy"),
                3,
                "kb/throttling-policy.md",
                gap.index());
    }

    @Override
    public void recordAnswer(String epicId, Question question, Answer answer) {
        // Real implementation: open a PR against the knowledge-base repo, dated and
        // attributed, then reindex into Qdrant once it merges.
        log.info("[kb] {} answered on {} -> would commit to {}",
                question.id(), epicId, question.targetKbFile());
    }

    @Override
    public List<Story> decompose(Epic epic, Journey journey) {
        log.info("[architect] decomposing {} ({} of {} stages settled)",
                epic.id(), journey.settled(), journey.stages().size());

        return List.of(
                new Story(epic.id() + "-1", "Tenant config lookup", 1, List.of()),
                new Story(epic.id() + "-2", "Token bucket primitive", 1, List.of()),
                new Story(epic.id() + "-3", "Redis-backed limiter", 2, List.of(epic.id() + "-2")),
                new Story(epic.id() + "-4", "Key on tenant identity", 2, List.of(epic.id() + "-1")),
                new Story(epic.id() + "-5", "Limits overview screen", 1, List.of(epic.id() + "-3")),
                new Story(epic.id() + "-6", "Request and approval flow", 1, List.of(epic.id() + "-5")));
    }

    @Override
    public List<AcceptanceCriterion> writeCriteria(Epic epic, Story story, Journey journey) {
        // A deliberately mixed split. Criteria that were all AUTOMATED would hide the thing
        // gate 1 exists to show: how much of an epic lands on a person regardless.
        return List.of(
                new AcceptanceCriterion("AC-1",
                        story.title() + " behaves as described.",
                        Testability.AUTOMATED, List.of("kb/throttling-policy.md")),
                new AcceptanceCriterion("AC-2",
                        "The wording is clear enough to act on.",
                        Testability.HUMAN_ONLY, List.of()));
    }

    @Override
    public Estimate estimate(String epicId, List<Story> stories, double reworkMultiplier) {
        // Real logic, not a stub. It outlives the fake agents around it, and phase 1 of
        // PHASES.md checks a hand-priced epic against exactly this arithmetic.
        Estimate estimate = new EstimateCalculator(costs.unitCosts(), reworkMultiplier)
                .estimate(stories, true);

        log.info("[estimate] {} -> ${} and {} human minutes across {} stories",
                epicId, estimate.dollars(), estimate.humanMinutes(), stories.size());
        return estimate;
    }

    @Override
    public void publishStatus(String epicId, Phase phase, String note) {
        // Real implementation: update the GitHub Issue. Status belongs on the board and
        // in Git — never in the knowledge base.
        log.info("[board] {} -> {} ({})", epicId, phase, note);
    }

    /** Exposed for the estimate calibration test. */
    static List<String> stageOrder() {
        return new ArrayList<>(List.of("pm", "ba", "architect", "designer", "dev", "reviewer", "qa"));
    }
}

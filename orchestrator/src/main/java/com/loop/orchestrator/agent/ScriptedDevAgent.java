package com.loop.orchestrator.agent;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A dev agent that writes no code, and is loud about it.
 *
 * <p>The default, for the same reason the scripted model is: a pipeline that quietly starts
 * editing a repository because someone ran it to see what it did is a worse failure than one
 * that does nothing.
 *
 * <p>It reports success so the orchestration downstream still runs — CI, review, QA and the
 * gates all get exercised — while every log line says no code was written. What it must
 * never do is make the pipeline look like it built something.
 */
public class ScriptedDevAgent implements DevAgent {

    private static final Logger log = LoggerFactory.getLogger(ScriptedDevAgent.class);

    /**
     * Plausible per-attempt cost, so the workflow's cost arithmetic has something real to
     * sum. Matches the tier prices in cost-model.properties.
     */
    private static final double SENIOR_COST = 1.85;
    private static final double MID_COST = 0.42;

    @Override
    public Result implement(Story story, List<AcceptanceCriterion> criteria,
                            Agent agent, int attempt, String previousFailure) {

        log.warn("[{}] {} attempt {} — SCRIPTED dev agent: no code was written. "
                        + "Set dev.provider=openhands to dispatch for real.",
                agent.name().toLowerCase(), story.id(), attempt);

        if (previousFailure != null && !previousFailure.isBlank()) {
            // Logged so a rework loop is visible in a scripted run too — otherwise the
            // loop looks identical to a story that succeeded first time.
            log.info("[{}] {} would be correcting: {}",
                    agent.name().toLowerCase(), story.id(), previousFailure);
        }

        double cost = agent == Agent.SENIOR ? SENIOR_COST : MID_COST;

        return new Result(true,
                "scripted: %d criteria, no code written".formatted(criteria.size()),
                "feat/" + story.id().toLowerCase() + " (scripted)",
                cost, 0, 0, null);
    }

    @Override
    public String describe() {
        return "scripted dev agent — writes no code";
    }
}

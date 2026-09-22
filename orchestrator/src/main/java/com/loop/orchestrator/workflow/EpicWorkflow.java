package com.loop.orchestrator.workflow;

import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.EpicStatus;
import com.loop.orchestrator.domain.Domain.GateDecision;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EpicWorkflow {

    String TASK_QUEUE = "loop-epics";

    /** Runs for the life of the epic — days, most of it asleep. */
    @WorkflowMethod
    EpicStatus run(Epic epic);

    /** A person answered a blocking question. This is what wakes the workflow up. */
    @SignalMethod
    void answerQuestion(Answer answer);

    /** A person passed or failed one of the three gates. */
    @SignalMethod
    void decideGate(GateDecision decision);

    /**
     * Read the live state without touching it. The board and epic-detail screens are
     * built on this — no separate status table, no second source of truth.
     */
    @QueryMethod
    EpicStatus status();
}

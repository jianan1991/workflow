package com.loop.orchestrator.workflow;

import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One story, from dispatch to merge. Runs as a child of the epic workflow.
 *
 * <p>Its workflow id is {@code <epicId>/<storyId>}, so the PR gate can be signalled from
 * outside without the epic having to relay it.
 */
@WorkflowInterface
public interface StoryWorkflow {

    static String workflowId(String epicId, String storyId) {
        return epicId + "/" + storyId;
    }

    @WorkflowMethod
    StoryStatus run(String epicId, Story story);

    /** Gate 3. Only reaches here when the story was not eligible for auto-approval. */
    @SignalMethod
    void decidePullRequest(GateDecision decision);

    @QueryMethod
    StoryStatus status();
}

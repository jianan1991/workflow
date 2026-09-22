package com.loop.orchestrator.workflow;

import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import java.util.List;

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

    /**
     * @param criteria what the reviewer and QA judge against. Passed in rather than fetched
     *                 so the story is judged against the criteria that existed when the
     *                 plan was approved, not whatever the BA would say today.
     */
    @WorkflowMethod
    StoryStatus run(String epicId, Story story, List<AcceptanceCriterion> criteria);

    /** Gate 3. Only reaches here when the story was not eligible for auto-approval. */
    @SignalMethod
    void decidePullRequest(GateDecision decision);

    @QueryMethod
    StoryStatus status();
}

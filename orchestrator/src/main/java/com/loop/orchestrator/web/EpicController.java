package com.loop.orchestrator.web;

import com.loop.orchestrator.domain.Domain.Answer;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.domain.Domain.EpicStatus;
import com.loop.orchestrator.domain.Domain.GateDecision;
import com.loop.orchestrator.domain.Domain.StoryStatus;
import com.loop.orchestrator.workflow.EpicWorkflow;
import com.loop.orchestrator.workflow.StoryWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stands in for the GitHub Issues webhook until that is wired.
 *
 * <p>Deliberately thin: no database, no status table. Live state comes from querying the
 * running workflow, which is what keeps the board from becoming a second source of truth.
 */
@RestController
@RequestMapping("/epics")
public class EpicController {

    private static final Logger log = LoggerFactory.getLogger(EpicController.class);

    private final WorkflowClient client;

    public EpicController(WorkflowClient client) {
        this.client = client;
    }

    /**
     * Start an epic.
     *
     * <p>The workflow id is the epic id, so a retry cannot start a second run of the same
     * epic. It used to be described as safe to retry and was not: the duplicate came back
     * as an uncaught WorkflowExecutionAlreadyStarted, which Spring turns into a 500. A
     * retry now gets a 409 naming the epic that is already running, and a request with no
     * usable id gets a 400 instead of a 500 from inside Temporal.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody Epic epic) {
        String rejection = unusable(epic);
        if (rejection != null) {
            return ResponseEntity.badRequest().body(Map.of("error", rejection));
        }

        EpicWorkflow workflow = client.newWorkflowStub(
                EpicWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(EpicWorkflow.TASK_QUEUE)
                        .setWorkflowId(epic.id())
                        .build());

        try {
            WorkflowClient.start(workflow::run, epic);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("epic {} is already running — not starting a second one", epic.id());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "epicId", epic.id(),
                    "error", "this epic is already running; GET /epics/" + epic.id()
                            + " for its state"));
        }

        log.info("started epic {}", epic.id());

        return ResponseEntity.accepted().body(Map.of(
                "epicId", epic.id(),
                "watch", "http://localhost:8080/namespaces/default/workflows/" + epic.id()));
    }

    /**
     * @return why this epic cannot be started, or null if it can
     */
    private static String unusable(Epic epic) {
        if (epic == null || epic.id() == null || epic.id().isBlank()) {
            return "an epic needs an id — it becomes the workflow id, so the run cannot be "
                    + "addressed without one";
        }
        if (epic.id().chars().anyMatch(Character::isWhitespace)) {
            return "an epic id cannot contain whitespace: '" + epic.id() + "'";
        }
        if (epic.id().contains("/")) {
            // StoryWorkflow.workflowId composes epicId + "/" + storyId, so a slash here
            // could produce a story id that collides with another epic's.
            return "an epic id cannot contain '/': story workflow ids are built from it";
        }
        if (epic.title() == null || epic.title().isBlank()) {
            return "an epic needs a title — the PM agent maps a journey from it";
        }
        return null;
    }

    /** Live state, read straight off the running workflow. */
    @GetMapping("/{id}")
    public ResponseEntity<EpicStatus> status(@PathVariable String id) {
        try {
            return ResponseEntity.ok(stub(id).status());
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Answer a blocking question. This is the signal that wakes a workflow which may have
     * been asleep for days.
     */
    @PostMapping("/{id}/answer")
    public ResponseEntity<Void> answer(@PathVariable String id, @RequestBody Answer answer) {
        try {
            stub(id).answerQuestion(answer);
            return ResponseEntity.accepted().build();
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** Pass or fail an epic-level gate — the plan, or the architecture. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable String id, @RequestBody GateDecision decision) {
        try {
            stub(id).decideGate(decision);
            return ResponseEntity.accepted().build();
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Gate 3, for one story. Signalled straight to the child workflow rather than relayed
     * through the epic — the story owns its own pull request.
     */
    @PostMapping("/{epicId}/stories/{storyId}/approve")
    public ResponseEntity<Void> approveStory(@PathVariable String epicId,
                                             @PathVariable String storyId,
                                             @RequestBody GateDecision decision) {
        try {
            client.newWorkflowStub(StoryWorkflow.class, StoryWorkflow.workflowId(epicId, storyId))
                    .decidePullRequest(decision);
            return ResponseEntity.accepted().build();
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** Live state of one story, read off its running workflow. */
    @GetMapping("/{epicId}/stories/{storyId}")
    public ResponseEntity<StoryStatus> storyStatus(@PathVariable String epicId,
                                                   @PathVariable String storyId) {
        try {
            return ResponseEntity.ok(client
                    .newWorkflowStub(StoryWorkflow.class, StoryWorkflow.workflowId(epicId, storyId))
                    .status());
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    private EpicWorkflow stub(String id) {
        return client.newWorkflowStub(EpicWorkflow.class, id);
    }
}

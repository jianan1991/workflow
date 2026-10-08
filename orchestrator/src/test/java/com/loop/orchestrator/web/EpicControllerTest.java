package com.loop.orchestrator.web;

import com.loop.orchestrator.activity.StubBuildActivities;
import com.loop.orchestrator.activity.StubPlanningActivities;
import com.loop.orchestrator.domain.Domain.Epic;
import com.loop.orchestrator.workflow.EpicWorkflow;
import com.loop.orchestrator.workflow.EpicWorkflowImpl;
import com.loop.orchestrator.workflow.StoryWorkflowImpl;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one endpoint that starts spending money.
 *
 * <p>It was documented as safe to retry and was not: a duplicate came back as an uncaught
 * {@code WorkflowExecutionAlreadyStarted}, which Spring renders as a 500, and a request
 * with no id reached {@code setWorkflowId(null)} and 500ed from inside Temporal. A client
 * retrying after a network timeout cannot tell either of those from a server that is
 * broken.
 */
class EpicControllerTest {

    @Nested
    @DisplayName("requests that cannot be started are refused before Temporal is touched")
    class Validation {

        /** No client at all: a rejected request must not reach one. */
        private final EpicController controller = new EpicController(null);

        @Test
        @DisplayName("no id, because the id becomes the workflow id")
        void blankIdIsRejected() {
            assertThat(controller.create(new Epic("  ", "Rate limiting", "body")).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.create(new Epic(null, "Rate limiting", "body")).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("whitespace or a slash in the id, which story workflow ids are built from")
        void malformedIdIsRejected() {
            assertThat(controller.create(new Epic("LOOP 14", "Rate limiting", "body"))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            ResponseEntity<Map<String, String>> slashed =
                    controller.create(new Epic("LOOP/14", "Rate limiting", "body"));
            assertThat(slashed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(slashed.getBody()).isNotNull();
            assertThat(slashed.getBody().get("error")).contains("story workflow ids");
        }

        @Test
        @DisplayName("no title, because the PM agent maps a journey from it")
        void blankTitleIsRejected() {
            assertThat(controller.create(new Epic("LOOP-14", " ", "body")).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("against a running Temporal")
    class AgainstTemporal {

        private TestWorkflowEnvironment env;

        @AfterEach
        void tearDown() {
            if (env != null) {
                env.close();
            }
        }

        @Test
        @DisplayName("starting the same epic twice reports the conflict instead of failing")
        void duplicateStartIsAConflict() {
            env = TestWorkflowEnvironment.newInstance(
                    TestEnvironmentOptions.newBuilder().setUseTimeskipping(false).build());

            Worker worker = env.newWorker(EpicWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(
                    EpicWorkflowImpl.class, StoryWorkflowImpl.class);
            worker.registerActivitiesImplementations(
                    new StubPlanningActivities("../cost-model.properties"),
                    new StubBuildActivities());
            env.start();

            EpicController controller = new EpicController(env.getWorkflowClient());
            Epic epic = new Epic("LOOP-31", "Per-tenant API rate limiting",
                    "Developers on the Starter plan can exhaust the API.");

            assertThat(controller.create(epic).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            ResponseEntity<Map<String, String>> again = controller.create(epic);

            assertThat(again.getStatusCode())
                    .as("a retry used to produce a 500, which a client cannot tell apart "
                            + "from a broken server")
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(again.getBody()).isNotNull();
            assertThat(again.getBody().get("error")).contains("already running");
            assertThat(again.getBody().get("epicId")).isEqualTo("LOOP-31");
        }
    }
}

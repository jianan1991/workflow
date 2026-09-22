package com.loop.orchestrator.config;

import com.loop.orchestrator.activity.BuildActivities;
import com.loop.orchestrator.activity.PlanningActivities;
import com.loop.orchestrator.workflow.EpicWorkflow;
import com.loop.orchestrator.workflow.EpicWorkflowImpl;
import com.loop.orchestrator.workflow.StoryWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.target:127.0.0.1:7233}") String target) {

        log.info("connecting to Temporal at {}", target);
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs,
                                         @Value("${temporal.namespace:default}") String namespace) {

        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    /**
     * One worker polling one task queue.
     *
     * <p>Workflow types are registered by class because Temporal constructs them itself on
     * every replay — they cannot be Spring beans. Activities are the opposite: they are
     * ordinary beans and get injected, which is where the model client, HTTP clients and
     * everything else non-deterministic belongs.
     */
    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient client,
                                       PlanningActivities planning,
                                       BuildActivities build) {
        WorkerFactory factory = WorkerFactory.newInstance(client);

        Worker worker = factory.newWorker(EpicWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(EpicWorkflowImpl.class, StoryWorkflowImpl.class);
        worker.registerActivitiesImplementations(planning, build);

        factory.start();
        log.info("worker polling task queue '{}'", EpicWorkflow.TASK_QUEUE);
        return factory;
    }
}

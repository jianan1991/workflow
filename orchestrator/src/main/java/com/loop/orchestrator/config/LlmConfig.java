package com.loop.orchestrator.config;

import com.loop.orchestrator.agent.ArchitectAgent;
import com.loop.orchestrator.agent.BaAgent;
import com.loop.orchestrator.agent.DevAgent;
import com.loop.orchestrator.agent.OpenHandsDevAgent;
import com.loop.orchestrator.agent.ScriptedDevAgent;
import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.agent.QaAgent;
import com.loop.orchestrator.agent.ReviewerAgent;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import com.loop.orchestrator.llm.CostingChatModel;
import com.loop.orchestrator.llm.LiteLlmChatModel;
import com.loop.orchestrator.llm.ScriptedChatModel;
import com.loop.orchestrator.policy.ReviewerIndependence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /**
     * What the scripted PM agent replies. Deliberately contains a GAP, so the default run
     * exercises the blocking path — an agent that never blocks tests nothing about the
     * part of this system that actually matters.
     */
    private static final String SCRIPTED_PM_JOURNEY = """
            {"stages":[
              {"index":1,"name":"Integrate","coverage":"KNOWN",
               "description":"A developer wires up the API with a key they generated themselves."},
              {"index":2,"name":"Grow","coverage":"KNOWN",
               "description":"Their traffic climbs month on month toward the plan ceiling."},
              {"index":3,"name":"Warning","coverage":"PARTIAL",
               "description":"Nothing states whether they are told before they hit the wall."},
              {"index":4,"name":"Blocked","coverage":"PARTIAL",
               "description":"Requests start returning 429. What the body carries is half answered."},
              {"index":5,"name":"Ask for more","coverage":"GAP",
               "description":"They want a higher ceiling. By which route, and who grants it?"},
              {"index":6,"name":"Resolved","coverage":"KNOWN",
               "description":"The new ceiling applies without a redeploy or a restart."}
            ]}
            """;

    /**
     * The PM makes two different calls, so the script has to tell them apart by the system
     * prompt it was given. Keying only on the agent name returns a journey to a question
     * request - which the agent then rejects, correctly and confusingly.
     */
    private static final String SCRIPTED_PM_QUESTION = """
            {"question":"Who grants a higher limit, and how quickly? If the answer is self-serve, this epic gains an admin surface and roughly doubles in size.",
             "searchQueries":["rate limit increase","plan ceiling approval","who approves a higher limit"],
             "targetKbFile":"throttling-policy.md"}
            """;

    /** The same journey with the gap closed, for the re-map after a person answers. */
    private static final String SCRIPTED_PM_JOURNEY_SETTLED =
            SCRIPTED_PM_JOURNEY.replace("\"coverage\":\"GAP\"", "\"coverage\":\"KNOWN\"");

    /**
     * {@code scripted} runs the whole pipeline with no key and no network. {@code litellm}
     * is the real thing.
     *
     * <p>Scripted is the default on purpose: the failure mode of defaulting to the real
     * provider is a stack that will not start, or worse, one that silently spends money in
     * a test run.
     */
    @Bean
    public ChatModel chatModel(
            @Value("${llm.provider:scripted}") String provider,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${cost.model-file:../cost-model.properties}") String costModelFile,
            AgentCostModel costs) {

        // Priced either way. A scripted run reports what it WOULD have cost at real prices,
        // so the cost path is exercised on every test run rather than first meeting
        // arithmetic on an invoice.
        return new CostingChatModel(underlying(provider, baseUrl, apiKey, costModelFile), costs);
    }

    private ChatModel underlying(String provider, String baseUrl, String apiKey,
                                 String costModelFile) {
        if (!"litellm".equalsIgnoreCase(provider)) {
            log.warn("llm.provider=scripted — NO MODEL WILL BE CALLED. This proves the "
                    + "plumbing, never the quality of an agent's answers. Set "
                    + "llm.provider=litellm with a key in .env before judging any agent.");
            return scripted();
        }

        Map<String, String> agentToTier = agentTiers(costModelFile);
        log.info("llm.provider=litellm at {} — real model calls, real money", baseUrl);
        return new LiteLlmChatModel(baseUrl, apiKey, agentToTier);
    }

    /**
     * Criteria for one stage, with a deliberately mixed testability split. A script where
     * everything is AUTOMATED would hide the thing the gate-1 screen exists to show: how
     * much of an epic lands on a person whatever the agents do.
     */
    private static final String SCRIPTED_BA_CRITERIA = """
            {"criteria":[
              {"text":"The 101st request in a 60 second window returns 429 with a Retry-After header.",
               "testability":"AUTOMATED","sources":["throttling-policy.md"]},
              {"text":"Two API keys belonging to one tenant draw from a single bucket.",
               "testability":"AUTOMATED","sources":["throttling-policy.md"]},
              {"text":"The 429 body states current usage and when the limit resets.",
               "testability":"AUTOMATED","sources":[]},
              {"text":"The 429 message is clear enough that a developer knows what to do next.",
               "testability":"HUMAN_ONLY","sources":[]}
            ],
             "unanswerable":[]}
            """;

    /** Four stories, two tiers, a real dependency chain, and no structural change. */
    private static final String SCRIPTED_ARCHITECT_STORIES = """
            {"structural":true,
             "stories":[
              {"id":"S-1","title":"Tenant config lookup","tier":1,"dependsOn":[]},
              {"id":"S-2","title":"Token bucket primitive","tier":1,"dependsOn":[]},
              {"id":"S-3","title":"Redis-backed limiter","tier":2,"dependsOn":["S-2"]},
              {"id":"S-4","title":"Key on tenant identity","tier":2,"dependsOn":["S-1"]}
            ]}
            """;

    /** Correct, but touching config it was not asked to touch - so no auto-approval. */
    private static final String SCRIPTED_REVIEW = """
            {"matchesCriteria":true,"scopeExceeded":true,
             "verdict":"Correct, but it edits application.yml, which the story did not authorise. Look hardest at the Redis failure path: it fails open.",
             "unmetCriteria":[],"outOfScopeFiles":["application.yml"]}
            """;

    /**
     * The demo script, exposed so a test can run the real activities against the same
     * model the application uses when no key is configured. A second copy in the test tree
     * would drift from this one, and the test would then prove the wiring against a script
     * nothing actually runs.
     */
    public static ScriptedChatModel scriptedModel() {
        return scripted();
    }

    private static ScriptedChatModel scripted() {
        Map<String, java.util.function.Function<ChatModel.ChatRequest, String>> script =
                new LinkedHashMap<>();

        // Answering a question closes the gap, so the epic can proceed on the second pass.
        // That is what makes the block-and-resume path runnable without a model.
        script.put(PmAgent.NAME, request -> {
            if (request.system().contains("Write the question you would ask")) {
                return SCRIPTED_PM_QUESTION;
            }
            return request.user().contains("Decisions already made")
                    ? SCRIPTED_PM_JOURNEY_SETTLED
                    : SCRIPTED_PM_JOURNEY;
        });

        script.put(BaAgent.NAME, request -> SCRIPTED_BA_CRITERIA);

        // Story ids are rewritten to the epic's own prefix, so the scripted graph stays
        // valid whatever epic runs through it.
        script.put(ArchitectAgent.NAME, request ->
                SCRIPTED_ARCHITECT_STORIES.replace("S-", epicPrefix(request.user()) + "-"));

        script.put(ReviewerAgent.NAME, request -> SCRIPTED_REVIEW);

        // QA has to answer every criterion it was given, so the scripted reply is built
        // from the prompt rather than fixed - a canned list would fail its own completeness
        // check the moment the criteria changed.
        script.put(QaAgent.NAME, QaAgentScript::replyFor);

        return new ScriptedChatModel(script);
    }

    /** Pulls the epic id back out of the prompt the architect was given. */
    static String epicPrefix(String prompt) {
        int start = prompt.indexOf("# Epic ");
        if (start < 0) {
            return "EPIC";
        }
        int from = start + "# Epic ".length();
        int colon = prompt.indexOf(':', from);
        return colon < 0 ? "EPIC" : prompt.substring(from, colon).trim();
    }

    /**
     * Every agent's actual model id, with tiers resolved. Shares {@code AgentCostModel}'s
     * resolution so the model this warns about is the same one that gets priced and the
     * same one that gets called.
     */
    static Map<String, String> resolvedModels(String costModelFile) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(costModelFile))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the cost model at " + costModelFile, e);
        }

        Map<String, String> models = new LinkedHashMap<>();
        new AgentCostModel(properties).derivations()
                .forEach(derivation -> models.put(derivation.agent(), derivation.model()));
        return models;
    }

    /**
     * Reads the agent-to-tier mapping from the same file that prices them, so an agent can
     * never be callable but unpriced, or priced but uncallable.
     */
    static Map<String, String> agentTiers(String costModelFile) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(costModelFile))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the cost model at " + costModelFile, e);
        }

        Map<String, String> tiers = new LinkedHashMap<>();
        for (String agent : AgentCostModel.AGENTS) {
            String direct = properties.getProperty("agent." + agent + ".model");
            String tier = properties.getProperty("agent." + agent + ".tier");
            // A direct model override wins, same as it does for pricing.
            tiers.put(agent, direct != null && !direct.isBlank() ? direct.trim() : tier);
        }
        return tiers;
    }

    /**
     * Checked once at startup and served from {@code /policy/reviewer-independence}, so a
     * person approving a plan sees it rather than having to remember a log line.
     *
     * <p>Warned about, not enforced. Refusing to start would be the wrong trade: a weakened
     * reviewer still catches plenty, and a stack that will not boot over a configuration
     * preference gets worked around within a day.
     */
    @Bean
    public ReviewerIndependence.Assessment reviewerIndependence(
            @Value("${cost.model-file:../cost-model.properties}") String costModelFile) {

        // Resolved models, not tier names. agentTiers() answers "which tier", and a tier
        // name like `review` attributes to no vendor at all - which would fire this warning
        // for the wrong reason and tell nobody anything useful.
        ReviewerIndependence.Assessment assessment =
                ReviewerIndependence.assess(resolvedModels(costModelFile));

        if (assessment.independent()) {
            log.info("reviewer independence: {} — {}",
                    assessment.reviewerModel(), assessment.finding());
        } else {
            log.warn(assessment.warningBanner());
        }
        return assessment;
    }

    /**
     * Shared by the estimator and the reviewer-independence check, so the model a figure is
     * priced against is the same one that gets called and the same one that gets warned about.
     */
    @Bean
    public AgentCostModel agentCostModel(
            @Value("${cost.model-file:../cost-model.properties}") String costModelFile) {

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(costModelFile))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the cost model at " + costModelFile, e);
        }
        return new AgentCostModel(properties);
    }

    @Bean
    public PmAgent pmAgent(ChatModel chatModel,
                           KnowledgeIndexer knowledge,
                           @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new PmAgent(chatModel, knowledge, maxOutputTokens);
    }

    @Bean
    public BaAgent baAgent(ChatModel chatModel,
                           KnowledgeIndexer knowledge,
                           @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new BaAgent(chatModel, knowledge, maxOutputTokens);
    }

    /**
     * {@code scripted} writes no code. {@code openhands} dispatches to the real harness.
     *
     * <p>Scripted by default, for the same reason the scripted model is: a pipeline that
     * starts editing a repository because somebody ran it to see what it did is a worse
     * failure than one that does nothing.
     */
    @Bean
    public DevAgent devAgent(
            @Value("${dev.provider:scripted}") String provider,
            @Value("${dev.executable:openhands}") String executable,
            @Value("${dev.workspace:}") String workspace,
            @Value("${dev.timeout-minutes:20}") int timeoutMinutes) {

        if (!"openhands".equalsIgnoreCase(provider)) {
            log.warn("dev.provider=scripted — NO CODE WILL BE WRITTEN. Everything downstream "
                    + "still runs, so CI, review, QA and the gates stay exercised.");
            return new ScriptedDevAgent();
        }

        if (workspace.isBlank()) {
            throw new IllegalStateException("dev.provider=openhands needs dev.workspace — "
                    + "without it the harness would start in whatever directory this process "
                    + "happens to be in and edit the wrong repository");
        }

        // Must stay under the activity's start-to-close timeout, or Temporal gives up on a
        // run that is still going and retries it on top of a half-edited workspace.
        Duration timeout = Duration.ofMinutes(timeoutMinutes);
        log.warn("dev.provider=openhands — the agent WILL edit {} and spend money. This "
                + "dispatch has never been verified against a real run.", workspace);

        return new OpenHandsDevAgent(executable, Path.of(workspace), timeout);
    }

    @Bean
    public ReviewerAgent reviewerAgent(
            ChatModel chatModel,
            @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new ReviewerAgent(chatModel, maxOutputTokens);
    }

    @Bean
    public QaAgent qaAgent(ChatModel chatModel,
                           @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new QaAgent(chatModel, maxOutputTokens);
    }

    @Bean
    public ArchitectAgent architectAgent(ChatModel chatModel,
                                         KnowledgeIndexer knowledge,
                                         @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new ArchitectAgent(chatModel, knowledge, maxOutputTokens);
    }
}

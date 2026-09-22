package com.loop.orchestrator.config;

import com.loop.orchestrator.agent.PmAgent;
import com.loop.orchestrator.cost.AgentCostModel;
import com.loop.orchestrator.knowledge.KnowledgeIndexer;
import com.loop.orchestrator.llm.ChatModel;
import com.loop.orchestrator.llm.LiteLlmChatModel;
import com.loop.orchestrator.llm.ScriptedChatModel;
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
            @Value("${cost.model-file:../cost-model.properties}") String costModelFile) {

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

    private static ScriptedChatModel scripted() {
        Map<String, java.util.function.Function<ChatModel.ChatRequest, String>> script =
                new LinkedHashMap<>();

        // Answering a question closes the gap, so the epic can proceed on the second pass.
        // That is what makes the block-and-resume path runnable without a model.
        script.put(PmAgent.NAME, request ->
                request.user().contains("Decisions already made")
                        ? SCRIPTED_PM_JOURNEY_SETTLED
                        : SCRIPTED_PM_JOURNEY);

        return new ScriptedChatModel(script);
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

    @Bean
    public PmAgent pmAgent(ChatModel chatModel,
                           KnowledgeIndexer knowledge,
                           @Value("${llm.max-output-tokens:4096}") int maxOutputTokens) {
        return new PmAgent(chatModel, knowledge, maxOutputTokens);
    }
}

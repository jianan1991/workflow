package com.loop.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loop.orchestrator.domain.Domain.AcceptanceCriterion;
import com.loop.orchestrator.domain.Domain.Agent;
import com.loop.orchestrator.domain.Domain.Story;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs OpenHands headless against a checkout, and reads what happened from its event stream.
 *
 * <pre>openhands --headless --json -f task.txt</pre>
 *
 * <p><b>Unverified against a real OpenHands run.</b> The invocation was taken from
 * docs.openhands.dev on 2026-09-22, but no repository has been named for the POC and no key
 * has been configured, so this has never executed. The JSONL event schema in particular is
 * read defensively: fields that are not found are reported as unknown rather than defaulted,
 * because a token count silently becoming zero is indistinguishable from a free run.
 *
 * <p>The working directory is set on the process rather than passed as a flag. The CLI
 * documentation does not describe a workspace option, and setting the process directory
 * works whether or not one exists.
 */
public class OpenHandsDevAgent implements DevAgent {

    private static final Logger log = LoggerFactory.getLogger(OpenHandsDevAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String executable;
    private final Path workspace;
    private final Duration timeout;

    public OpenHandsDevAgent(String executable, Path workspace, Duration timeout) {
        this.executable = executable;
        this.workspace = workspace;
        this.timeout = timeout;
    }

    @Override
    public Result implement(Story story, List<AcceptanceCriterion> criteria,
                            Agent agent, int attempt, String previousFailure) {

        if (!Files.isDirectory(workspace)) {
            // Better to say the workspace is missing than to let the harness start in
            // whatever directory this process happens to be in and edit the wrong repository.
            return Result.failed("workspace " + workspace.toAbsolutePath()
                    + " does not exist — set dev.workspace to the checkout the agent works in");
        }

        Path taskFile;
        try {
            // A file rather than -t: a task carrying acceptance criteria and a previous
            // failure runs to hundreds of characters, and quoting that through a shell is a
            // source of corruption nobody enjoys debugging.
            taskFile = Files.createTempFile("loop-task-", ".txt");
            Files.writeString(taskFile,
                    DevAgent.taskFor(story, criteria, attempt, previousFailure),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the task file", e);
        }

        try {
            return run(story, agent, attempt, taskFile);
        } finally {
            try {
                Files.deleteIfExists(taskFile);
            } catch (IOException e) {
                log.warn("could not delete {}", taskFile, e);
            }
        }
    }

    private Result run(Story story, Agent agent, int attempt, Path taskFile) {
        List<String> command = List.of(
                executable, "--headless", "--json", "-f", taskFile.toAbsolutePath().toString());

        log.info("[{}] {} attempt {} — dispatching to {} in {}",
                agent.name().toLowerCase(), story.id(), attempt, executable, workspace);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return Result.failed("could not start '" + executable + "': " + e.getMessage()
                    + " — is OpenHands installed and on PATH?");
        }

        List<String> events = new ArrayList<>();
        try {
            // Drained on a separate thread: a process whose output nobody reads fills its
            // pipe buffer and blocks forever, which looks exactly like a hung agent.
            Thread drain = new Thread(() -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    reader.lines().forEach(events::add);
                } catch (IOException e) {
                    log.warn("stopped reading agent output early", e);
                }
            });
            drain.setDaemon(true);
            drain.start();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Result.failed("timed out after " + timeout.toMinutes()
                        + " minutes — the harness was killed, and anything it half-wrote is "
                        + "still in the workspace");
            }
            drain.join(TimeUnit.SECONDS.toMillis(5));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.failed("interrupted while waiting for the agent");
        }

        int exit = process.exitValue();
        Usage usage = readUsage(events);

        if (exit != 0) {
            return new Result(false, "", "", usage.costUsd(), usage.inputTokens(),
                    usage.outputTokens(),
                    "exited " + exit + ": " + tail(events));
        }

        log.info("[{}] {} attempt {} — finished, {} event(s), {} in / {} out tokens",
                agent.name().toLowerCase(), story.id(), attempt, events.size(),
                usage.inputTokens(), usage.outputTokens());

        return new Result(true, tail(events),
                "feat/" + story.id().toLowerCase(),
                usage.costUsd(), usage.inputTokens(), usage.outputTokens(), null);
    }

    /**
     * Pulls token usage out of the event stream if it is there.
     *
     * <p>Read defensively on purpose. The event schema has not been verified against a real
     * run, and guessing at field names would produce confident zeros — which read as a free
     * run rather than as an unknown one. When nothing is found, that is logged.
     */
    static Usage readUsage(List<String> events) {
        int input = 0;
        int output = 0;
        double cost = 0.0;

        for (String line : events) {
            if (line == null || line.isBlank() || !line.trim().startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(line);
                input = Math.max(input, firstInt(node, "prompt_tokens", "input_tokens"));
                output = Math.max(output, firstInt(node, "completion_tokens", "output_tokens"));
                cost = Math.max(cost, firstDouble(node, "accumulated_cost", "cost"));
            } catch (Exception ignored) {
                // A line that is not an event is not a failure. The stream carries logs too.
            }
        }

        if (input == 0 && output == 0) {
            log.warn("no token usage found in the agent's output — its spend is being "
                    + "reported as zero, which is not the same as free");
        }
        return new Usage(input, output, cost);
    }

    private static int firstInt(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode found = root.findValue(name);
            if (found != null && found.isNumber()) {
                return found.asInt();
            }
        }
        return 0;
    }

    private static double firstDouble(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode found = root.findValue(name);
            if (found != null && found.isNumber()) {
                return found.asDouble();
            }
        }
        return 0.0;
    }

    /** The last few lines, which is where a harness puts what went wrong. */
    private static String tail(List<String> events) {
        int from = Math.max(0, events.size() - 5);
        return String.join("\n", events.subList(from, events.size()));
    }

    @Override
    public String describe() {
        return "openhands headless (" + executable + ") in " + workspace
                + ", timeout " + timeout.toMinutes() + "m — UNVERIFIED against a real run";
    }

    record Usage(int inputTokens, int outputTokens, double costUsd) {
    }
}

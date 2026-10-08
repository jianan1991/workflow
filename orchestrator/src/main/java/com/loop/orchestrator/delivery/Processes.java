package com.loop.orchestrator.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Running an external command and reading what it said, once, correctly.
 *
 * <p>Both halves of this are easy to get wrong and were already got wrong once here. A
 * process whose output nobody reads fills its pipe buffer and blocks forever, which looks
 * exactly like a hung tool. And draining on another thread introduces a data race: a
 * {@code join(timeout)} returns whether or not the reader has finished, so iterating the
 * list it is still appending to throws {@link java.util.ConcurrentModificationException}
 * out of whatever called it.
 *
 * <p>Git and Maven both need this, so it lives in one place rather than being re-derived.
 * {@code OpenHandsDevAgent} has its own copy of the same logic and should move here.
 */
final class Processes {

    private static final Logger log = LoggerFactory.getLogger(Processes.class);

    /** How long to keep reading after the process is done before reporting a partial read. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    private Processes() {
    }

    /**
     * @param exitCode     -1 when the process was killed at the timeout, because
     *                     {@code exitValue()} on a just-destroyed process throws
     * @param fullyDrained false when output was still arriving when it was read, which makes
     *                     everything derived from it a floor rather than a total
     */
    record Outcome(int exitCode, List<String> output, boolean timedOut, boolean fullyDrained,
                   String startFailure) {

        boolean started() {
            return startFailure == null;
        }

        boolean succeeded() {
            return started() && !timedOut && exitCode == 0;
        }

        String text() {
            return String.join("\n", output);
        }

        /** The end of the output, which is where a tool puts what went wrong. */
        String tail(int lines) {
            int from = Math.max(0, output.size() - lines);
            return String.join("\n", output.subList(from, output.size()));
        }
    }

    static Outcome run(List<String> command, Path directory, Duration timeout) {
        log.info("running {} in {}", String.join(" ", command), directory);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            // Not an exception out of here. A missing executable is a business outcome the
            // caller has to report to a person, not a crash that kills a story workflow.
            return new Outcome(-1, List.of(), false, true,
                    "could not start '" + command.getFirst() + "': " + e.getMessage());
        }

        List<String> output = Collections.synchronizedList(new ArrayList<>());

        Thread drain = new Thread(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                reader.lines().forEach(output::add);
            } catch (IOException e) {
                log.warn("stopped reading output of {} early", command.getFirst(), e);
            }
        });
        drain.setDaemon(true);
        drain.start();

        boolean timedOut;
        try {
            timedOut = !process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (timedOut) {
                // Killing it closes the pipe, which ends the reader, so what it managed to
                // read survives to be reported rather than dying with the process.
                process.destroyForcibly();
            }
            drain.join(DRAIN_GRACE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Outcome(-1, List.of(), false, true,
                    "interrupted while waiting for " + command.getFirst());
        }

        boolean fullyDrained = !drain.isAlive();
        List<String> snapshot;
        synchronized (output) {
            snapshot = List.copyOf(output);
        }

        int exitCode = timedOut ? -1 : process.exitValue();
        return new Outcome(exitCode, snapshot, timedOut, fullyDrained, null);
    }
}

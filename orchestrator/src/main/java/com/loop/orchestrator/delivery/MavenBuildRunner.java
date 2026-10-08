package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the repository's Maven build and measures the change, not the repository.
 *
 * <p><b>The thresholds are applied here, not by the build.</b> The build is asked for
 * evidence — run the tests, write a JaCoCo report, write a PIT report — with its own
 * thresholds turned off. Deciding whether the numbers are good enough stays in Java where
 * it can be unit-tested, and the gate measures the diff rather than the project. A
 * project-wide rule on a codebase that is still mostly stubs fails for reasons nobody can
 * act on, which is how teams learn to pass {@code -DskipTests}.
 *
 * <p>The mutation run is scoped to the classes the diff touched. Unscoped PIT on a whole
 * repository takes long enough that it stops being run at all.
 */
public class MavenBuildRunner implements BuildRunner {

    private static final Logger log = LoggerFactory.getLogger(MavenBuildRunner.class);

    private final String executable;
    private final Path workspace;
    private final Path module;
    private final Duration timeout;
    private final double diffCoverageThreshold;
    private final double mutationThreshold;
    private final SourceControl scm;

    public MavenBuildRunner(String executable, Path workspace, String module, Duration timeout,
                            double diffCoverageThreshold, double mutationThreshold,
                            SourceControl scm) {
        this.executable = executable;
        this.workspace = workspace;
        this.module = workspace.resolve(module).normalize();
        this.timeout = timeout;
        this.diffCoverageThreshold = diffCoverageThreshold;
        this.mutationThreshold = mutationThreshold;
        this.scm = scm;
    }

    @Override
    public CiResult run(Diff diff, String branch, int attempt) {
        // Before anything: is the working tree the one we are about to make claims about?
        // A pass measured on the wrong branch is worse than no measurement, because the
        // auto-approval gate believes it.
        String checkedOut = scm.currentBranch();
        if (branch != null && !branch.isBlank() && checkedOut != null
                && !checkedOut.equals(branch)) {
            return CiResult.notRun(("the workspace is on '%s' but the story's branch is "
                    + "'%s' — refusing to report a measurement of the wrong tree")
                    .formatted(checkedOut, branch));
        }

        Processes.Outcome build = Processes.run(command(diff), module, timeout);

        if (!build.started()) {
            return CiResult.notRun(build.startFailure() + " — is Maven on PATH?");
        }
        if (build.timedOut()) {
            return CiResult.notRun("the build timed out after " + timeout.toMinutes()
                    + " minutes; nothing was measured");
        }
        if (build.exitCode() != 0) {
            // Measured: the build ran and said no. That is a real failure the agent can be
            // asked to fix - so it has to be told what broke. The tail of the output is
            // Maven's footer, and "the build failed: [INFO] ------" is what the agent was
            // getting handed as the thing to correct.
            return new CiResult(true, false, 0.0, 0.0,
                    "the build failed: " + whatBroke(build));
        }

        return measure(diff, build);
    }

    private CiResult measure(Diff diff, Processes.Outcome build) {
        List<Path> coverageReports = reportsNamed("jacoco.xml");
        if (coverageReports.isEmpty()) {
            return CiResult.notRun("the build passed but wrote no JaCoCo report — "
                    + "coverage is unmeasured, so this cannot auto-approve");
        }

        JacocoReport.Measurement coverage = new JacocoReport.Measurement(0, 0);
        for (Path report : coverageReports) {
            JacocoReport.Measurement part = JacocoReport.parse(read(report)).diffCoverage(diff);
            coverage = new JacocoReport.Measurement(
                    coverage.executableLines() + part.executableLines(),
                    coverage.coveredLines() + part.coveredLines());
        }

        PitReport.Score mutation = new PitReport.Score(0, 0);
        for (Path report : reportsNamed("mutations.xml")) {
            PitReport.Score part = PitReport.parse(read(report)).forDiff(diff);
            mutation = new PitReport.Score(
                    mutation.considered() + part.considered(),
                    mutation.detected() + part.detected());
        }

        boolean coverageOk = !coverage.hasAnything()
                || coverage.ratio() >= diffCoverageThreshold;
        boolean mutationOk = !mutation.hasAnything()
                || mutation.ratio() >= mutationThreshold;

        String note = describeMeasurement(coverage, mutation);
        log.info("[ci] {}{}", note, build.fullyDrained() ? "" : " (output was truncated)");

        return new CiResult(true, coverageOk && mutationOk,
                coverage.ratio(), mutation.ratio(), note);
    }

    /**
     * Says which numbers are real and which are absences. "Mutation 100%" and "nothing to
     * mutate" are both reported as a pass, and a person reading the pull request has to be
     * able to tell them apart.
     */
    private String describeMeasurement(JacocoReport.Measurement coverage,
                                       PitReport.Score mutation) {
        String coverageText = coverage.hasAnything()
                ? "diff coverage %.0f%% (%d of %d changed lines, gate %.0f%%)".formatted(
                        coverage.ratio() * 100, coverage.coveredLines(),
                        coverage.executableLines(), diffCoverageThreshold * 100)
                : "no executable lines changed, so there is no diff coverage to report";

        String mutationText = mutation.hasAnything()
                ? "mutation %.0f%% (%d of %d killed, gate %.0f%%)".formatted(
                        mutation.ratio() * 100, mutation.detected(), mutation.considered(),
                        mutationThreshold * 100)
                : "no mutations landed on the changed lines";

        return coverageText + "; " + mutationText;
    }

    private List<String> command(Diff diff) {
        List<String> command = new ArrayList<>(List.of(
                executable, "-B", "test", "jacoco:report"));

        List<String> classes = diff.changedMainClasses();
        if (classes.isEmpty()) {
            // Nothing in main changed, so there is nothing to mutate. Running PIT anyway
            // would mutate the whole project to prove a point about a test-only change.
            log.info("[ci] no main classes changed; skipping the mutation run");
            return List.copyOf(command);
        }

        command.add("org.pitest:pitest-maven:mutationCoverage");
        command.add("-DtargetClasses=" + String.join(",", classes));

        // The tests are scoped as well as the classes, and this is not an optimisation.
        // PIT re-runs the suite to establish a baseline and refuses to continue unless it
        // is green - so one timing-sensitive test anywhere in the repository makes the
        // mutation gate unusable for every story. Found exactly that way: a Temporal
        // workflow test that passes under Surefire failed under PIT's own run, and the
        // whole gate came back as "the build failed".
        //
        // The cost of scoping: a test that covers a changed class from another package no
        // longer counts towards its score. That shows up as a lower number rather than a
        // wrong one, and the counts are in the note.
        command.add("-DtargetTests=" + String.join(",", packagesOf(classes)));
        command.add("-DoutputFormats=XML");
        command.add("-DtimestampedReports=false");
        // Zero, deliberately: the build's job is to produce the number, not to judge it.
        command.add("-DmutationThreshold=0");

        return List.copyOf(command);
    }

    /** {@code com.loop.cost.Estimate} -> {@code com.loop.cost.*}, where its tests live. */
    private static List<String> packagesOf(List<String> classes) {
        return classes.stream()
                .map(name -> name.contains(".")
                        ? name.substring(0, name.lastIndexOf('.')) + ".*"
                        : "*")
                .distinct()
                .toList();
    }

    /** Walks the module for reports, so a multi-module repository is handled too. */
    private List<Path> reportsNamed(String fileName) {
        try (Stream<Path> files = Files.walk(module, 6)) {
            return files.filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(path -> path.toString().contains("target"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not look for " + fileName, e);
        }
    }

    /**
     * The lines that say what went wrong: Maven's own errors, and the test failures
     * Surefire reports. Falls back to the tail only when neither is present, because
     * something is better than nothing when a build fails in a way we have not seen.
     */
    private static String whatBroke(Processes.Outcome build) {
        List<String> interesting = build.output().stream()
                .filter(line -> line.startsWith("[ERROR]")
                        || line.contains("FAILURE!")
                        || line.contains("<<< ERROR!")
                        || line.contains("Tests run:") && line.contains("Failures:")
                                && !line.contains("Failures: 0"))
                .filter(line -> !line.contains("To see the full stack trace"))
                .filter(line -> !line.contains("Re-run Maven using"))
                .filter(line -> !line.contains("For more information about the errors"))
                .filter(line -> !line.contains("[Help 1]"))
                .filter(line -> !line.trim().equals("[ERROR]"))
                .limit(12)
                .toList();

        return interesting.isEmpty()
                ? build.tail(15)
                : String.join("\n", interesting);
    }

    private static byte[] read(Path report) {
        try {
            return Files.readAllBytes(report);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + report, e);
        }
    }

    @Override
    public String describe() {
        return "%s in %s, gates: diff coverage %.0f%%, mutation %.0f%%".formatted(
                executable, module, diffCoverageThreshold * 100, mutationThreshold * 100);
    }
}

package com.loop.orchestrator.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The real thing: {@code git diff} in the workspace the dev agent edited.
 *
 * <p>Uses the three-dot form, {@code base...branch}, which asks "what has the branch added
 * since it left the base". The two-dot form also reports everything that landed on the base
 * meanwhile, so a busy main branch would make an agent look responsible for other people's
 * commits — and the reviewer would mark it down for them.
 */
public class GitSourceControl implements SourceControl {

    private static final Logger log = LoggerFactory.getLogger(GitSourceControl.class);

    /**
     * A diff this long stops being reviewable. The cap exists to protect the reviewer's
     * context: the acceptance criteria and the instructions have to fit alongside it, and a
     * model that silently loses the criteria will approve whatever it was shown.
     */
    private static final int MAX_DIFF_LINES = 1500;

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final String executable;
    private final Path workspace;
    private final String baseBranch;

    public GitSourceControl(String executable, Path workspace, String baseBranch) {
        this.executable = executable;
        this.workspace = workspace;
        this.baseBranch = baseBranch;
    }

    @Override
    public Diff diffFor(String branch) {
        if (branch == null || branch.isBlank()) {
            return Diff.empty("no branch — the dev agent did not report one, so there is "
                    + "nothing to diff");
        }
        // Asked of git rather than inferred from the layout. The first version of this
        // checked that `.git` was a directory, which is wrong for a linked worktree and
        // for a submodule - in both, `.git` is a file holding a path. It refused a
        // perfectly good checkout and reported "no git repository", which is the kind of
        // false negative that reads as a configuration mistake for an afternoon.
        Processes.Outcome insideRepository = Processes.run(
                List.of(executable, "rev-parse", "--is-inside-work-tree"), workspace, TIMEOUT);

        if (!insideRepository.succeeded()
                || !"true".equals(insideRepository.text().trim())) {
            return Diff.empty("no git work tree at " + workspace.toAbsolutePath()
                    + " — set dev.workspace to the checkout the agent works in");
        }

        String range = baseBranch + "..." + branch;
        Processes.Outcome outcome = Processes.run(
                List.of(executable, "diff", range), workspace, TIMEOUT);

        if (!outcome.started()) {
            return Diff.empty(outcome.startFailure() + " — is git on PATH?");
        }
        if (outcome.timedOut()) {
            return Diff.empty("git diff " + range + " timed out after "
                    + TIMEOUT.toMinutes() + " minutes");
        }
        if (outcome.exitCode() != 0) {
            // Usually an unknown revision: the agent reported a branch it never pushed, or
            // named the base wrongly. Both need a person, and both must say which.
            return Diff.empty("git diff " + range + " failed: " + outcome.tail(5));
        }

        List<String> lines = outcome.output();
        boolean truncated = lines.size() > MAX_DIFF_LINES;
        String unified = truncated
                ? String.join("\n", lines.subList(0, MAX_DIFF_LINES))
                        + "\n\n[diff truncated after " + MAX_DIFF_LINES + " of "
                        + lines.size() + " lines]"
                : outcome.text();

        if (!outcome.fullyDrained()) {
            log.warn("git diff output was still arriving when it was read; the change may "
                    + "be larger than what the reviewer sees");
        }

        // Line numbers come from the whole output, not the truncated text: a gate that
        // measured only the part the reviewer could read would reward a big diff.
        Diff diff = new Diff(unified, UnifiedDiff.parse(outcome.text()), truncated);

        log.info("[diff] {} -> {} file(s), {} changed line(s){}", range,
                diff.files().size(), diff.changedLineCount(),
                truncated ? ", truncated for the reviewer" : "");
        return diff;
    }

    @Override
    public String currentBranch() {
        Processes.Outcome outcome = Processes.run(
                List.of(executable, "rev-parse", "--abbrev-ref", "HEAD"), workspace, TIMEOUT);

        if (!outcome.succeeded() || outcome.output().isEmpty()) {
            log.warn("could not establish the checked-out branch in {}", workspace);
            return null;
        }
        return outcome.output().getFirst().trim();
    }

    @Override
    public String describe() {
        return "git diff " + baseBranch + "...<branch> in " + workspace;
    }
}

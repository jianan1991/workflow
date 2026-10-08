package com.loop.orchestrator.delivery;

/**
 * Reads the change an agent made out of the repository it made it in.
 *
 * <p>A seam, for the same reason {@code DevAgent} is one: the fake belongs at the boundary
 * where the real thing touches a repository, not inside the activity that uses it. That way
 * a test can drive a story through review and QA with a diff it controls, and nothing in
 * the orchestration knows whether git was involved.
 */
public interface SourceControl {

    /**
     * The change on {@code branch} relative to the base branch.
     *
     * <p>Never throws for an ordinary failure — a missing branch, a repository that is not
     * there, a git that is not installed. Those come back as an empty {@link Diff} whose
     * text says what happened, because the reviewer and the CI gate both have to be told
     * "there is nothing to look at and here is why" rather than crash the story workflow.
     */
    Diff diffFor(String branch);

    /**
     * Which branch the working tree is actually on, or null if that cannot be established.
     *
     * <p>Here because a build runner needs it. Measuring coverage on one tree and
     * reporting it against another story's branch would hand the auto-approval gate a
     * number it has no right to believe, and null has to mean "unknown" rather than a
     * plausible default for the same reason.
     */
    String currentBranch();

    /** What is actually reading the repository, for the startup log. */
    String describe();
}

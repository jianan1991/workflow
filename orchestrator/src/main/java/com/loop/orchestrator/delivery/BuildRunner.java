package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;

/**
 * Runs the target repository's own build and reports what it measured.
 *
 * <p>The seam matters here more than anywhere else in the pipeline, because this is the one
 * component whose output decides whether a story can merge with nobody watching. A fake
 * that reports a pass is the most dangerous object in the system — which is exactly what
 * {@code runCi} was before this existed, returning {@code passed = true} with zero coverage
 * and zero mutation score.
 *
 * <p>So the contract is: never report a pass you did not measure. {@link CiResult#notRun}
 * exists for that, and {@link CiResult#trustworthy()} is what the auto-approval gate reads.
 */
public interface BuildRunner {

    /**
     * @param diff what changed, because the numbers worth gating on are scoped to it:
     *             coverage of the lines this change added, and mutation of the classes it
     *             touched. Project-wide figures move too slowly for one story to shift.
     */
    CiResult run(Diff diff, String branch, int attempt);

    String describe();
}

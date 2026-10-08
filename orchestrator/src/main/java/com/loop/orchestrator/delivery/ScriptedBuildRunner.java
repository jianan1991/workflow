package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No build, and it says so in the only way the gate respects.
 *
 * <p>{@link CiResult#notRun} rather than a pass. This is the object that was wrong before:
 * {@code runCi} used to return {@code passed = true} with zero coverage and zero mutation
 * score, which combined with auto-approval to merge a tier 1 story on the strength of a
 * build that never ran.
 */
public class ScriptedBuildRunner implements BuildRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptedBuildRunner.class);

    @Override
    public CiResult run(Diff diff, String branch, int attempt) {
        log.warn("[ci] ci.provider=scripted — no build ran for {} attempt {}, so coverage "
                + "and mutation are unmeasured. Nothing can auto-approve.", branch, attempt);

        return CiResult.notRun("ci.provider=scripted — no build ran, nothing measured");
    }

    @Override
    public String describe() {
        return "scripted build runner — runs no build";
    }
}

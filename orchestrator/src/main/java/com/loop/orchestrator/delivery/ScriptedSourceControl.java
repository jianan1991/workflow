package com.loop.orchestrator.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No repository, and loud about it.
 *
 * <p>The default, for the same reason the scripted dev agent is: a pipeline that starts
 * reading and reporting on a real checkout because someone ran it to see what it did is a
 * worse failure than one that does nothing.
 *
 * <p>What it must never do is return a plausible-looking diff. The reviewer would then mark
 * invented code against real acceptance criteria and the verdict would mean nothing. An
 * empty diff whose text says why is the honest answer, and both the reviewer and the CI
 * gate are built to handle it.
 */
public class ScriptedSourceControl implements SourceControl {

    private static final Logger log = LoggerFactory.getLogger(ScriptedSourceControl.class);

    @Override
    public Diff diffFor(String branch) {
        log.warn("[diff] scm.provider=scripted — no repository is being read, so there is no "
                + "diff for {}. Set scm.provider=git with dev.workspace pointing at a "
                + "checkout.", branch);

        return Diff.empty("no diff: scm.provider=scripted, so nothing read the repository");
    }

    @Override
    public String currentBranch() {
        // Not "main". A guess here would let a build runner believe it was measuring the
        // right tree.
        return null;
    }

    @Override
    public String describe() {
        return "scripted source control — reads no repository";
    }
}

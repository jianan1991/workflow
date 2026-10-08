package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;

/**
 * What the pull request says.
 *
 * <p>Separate and pure, so the wording can be tested without opening anything. It is also
 * the one artefact a person actually reads, so it is held to a rule: every claim is either
 * measured or attributed, and an absence is written as an absence. "Coverage unmeasured"
 * and "coverage 0%" mean opposite things to a reviewer, and the design-system rule applies
 * here too — machine output in a code block, human-facing prose outside it.
 */
final class PullRequestBody {

    private PullRequestBody() {
    }

    static String of(Story story, Diff diff, CiResult ci, ReviewResult review, QaResult qa) {
        StringBuilder body = new StringBuilder();

        body.append("Story **").append(story.id()).append("** — tier ")
                .append(story.tier()).append(", written by an agent.\n\n");

        body.append("### What the checks measured\n\n");
        body.append("| Check | Result |\n| :-- | :-- |\n");
        body.append("| CI | ").append(ci.measured() ? escape(ci.note())
                : "**nothing measured** — " + escape(ci.note())).append(" |\n");
        body.append("| Reviewer | ").append(review.matchesCriteria()
                        ? "matches the acceptance criteria" : "**does not match the criteria**")
                .append(review.scopeExceeded() ? ", and goes outside the ticket" : "")
                .append(" |\n");
        body.append("| QA | ").append(describeQa(qa)).append(" |\n");
        body.append("| Diff | ").append(diff.files().size()).append(" file(s), ")
                .append(diff.changedLineCount()).append(" changed line(s)")
                .append(diff.truncated() ? ", **truncated before the reviewer saw it**" : "")
                .append(" |\n\n");

        body.append("### The reviewer's verdict\n\n> ")
                .append(escape(review.verdict())).append("\n\n");

        body.append("### QA's evidence\n\n> ").append(escape(qa.evidence())).append("\n\n");

        if (!ci.trustworthy()) {
            body.append("---\n\n**This cannot be auto-approved.** CI did not produce a "
                    + "measurement that can be gated on, so a person has to read the diff "
                    + "rather than the numbers.\n");
        }

        return body.toString();
    }

    private static String describeQa(QaResult qa) {
        String outcome = switch (qa.outcome()) {
            case PASS -> "passed";
            case CODE_DEFECT -> "**found a defect**";
            case CRITERION_WRONG -> "**says the criterion is wrong, not the code**";
            case UNVERIFIABLE -> "**could not verify it**";
        };
        String unverified = qa.unverifiableCount() > 0
                ? " — %d criteri%s need a person's judgement".formatted(
                        qa.unverifiableCount(), qa.unverifiableCount() == 1 ? "on" : "a")
                : "";

        return outcome + unverified;
    }

    /** Keeps a model's verdict from breaking the table it is rendered in. */
    private static String escape(String text) {
        if (text == null || text.isBlank()) {
            return "(nothing reported)";
        }
        return text.replace("|", "\\|").replace("\n", " ").trim();
    }

    static String title(Story story) {
        return "%s: %s".formatted(story.id(), story.title());
    }

    /** Only used when QA routed somewhere a title should say so. */
    static boolean needsAPerson(CiResult ci, ReviewResult review, QaResult qa) {
        return !ci.trustworthy()
                || !review.matchesCriteria()
                || review.scopeExceeded()
                || qa.outcome() != QaOutcome.PASS
                || qa.unverifiableCount() > 0;
    }
}

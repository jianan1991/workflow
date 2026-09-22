package com.loop.orchestrator.config;

import com.loop.orchestrator.llm.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the scripted QA reply from the prompt it was given.
 *
 * <p>Fixed text will not do here. QA must answer every criterion it receives, and a canned
 * list of verdicts fails that completeness check the moment the criteria change — which
 * would look like a bug in the agent rather than in the script.
 *
 * <p>The verdicts it produces are chosen to exercise all four outcomes and all three
 * routes, because a script where everything passes tests only the path that never needs
 * anyone.
 */
final class QaAgentScript {

    /** Matches the `- AC-1: text  [TESTABILITY]` lines the QA prompt lists. */
    private static final Pattern CRITERION =
            Pattern.compile("^- (\\S+): (.*?)\\s*\\[(\\w+)]\\s*$", Pattern.MULTILINE);

    private QaAgentScript() {
    }

    static String replyFor(ChatModel.ChatRequest request) {
        List<String> verdicts = new ArrayList<>();
        Matcher matcher = CRITERION.matcher(request.user());

        while (matcher.find()) {
            String id = matcher.group(1);
            String text = matcher.group(2).toLowerCase();
            String testability = matcher.group(3);

            if ("HUMAN_ONLY".equals(testability) || "OBSERVABLE".equals(testability)) {
                // Captured, not judged. These become a person's checklist rather than a
                // failure, and they were already flagged at the epic gate.
                verdicts.add(verdict(id, "UNVERIFIABLE",
                        "Response captured for a person to judge; no machine check settles this."));
            } else if (text.contains("body") || text.contains("usage")) {
                // The empty 429 body: the status code is right, the body is missing, and
                // unit tests asserting only the status would have passed.
                verdicts.add(verdict(id, "CODE_DEFECT",
                        "429 returned with content-length: 0; the body carries neither usage nor reset time."));
            } else {
                verdicts.add(verdict(id, "PASS",
                        "Observed directly in the run output."));
            }
        }

        // Something dangerous that no criterion covers. It routes to the PM, because there
        // is no criterion to fix - which is the case people most often get wrong.
        String concerns = """
                {"outcome":"CRITERION_WRONG",
                 "evidence":"With Redis stopped, 1000 of 1000 requests returned 200. The limiter fails open and no criterion covers it."}""";

        return "{\"verdicts\":[" + String.join(",", verdicts) + "],\"concerns\":["
                + (verdicts.isEmpty() ? "" : concerns) + "]}";
    }

    private static String verdict(String id, String outcome, String evidence) {
        return "{\"criterionId\":\"%s\",\"outcome\":\"%s\",\"evidence\":\"%s\"}"
                .formatted(id, outcome, evidence);
    }
}

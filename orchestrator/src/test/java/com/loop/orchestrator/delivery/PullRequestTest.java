package com.loop.orchestrator.delivery;

import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.PullRequest;
import com.loop.orchestrator.domain.Domain.QaOutcome;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-off to a person.
 *
 * <p>The GitHub client is exercised against a real HTTP server bound to a loopback port,
 * which is the whole reason it talks to the REST API rather than shelling out to the
 * {@code gh} CLI. A CLI wrapper could only have been verified by pointing it at a real
 * repository — this project already has one component that can only be checked that way,
 * in the dev agent, and did not need a second.
 */
class PullRequestTest {

    private static final Story STORY = new Story("LOOP-14-3", "Redis-backed limiter", 2,
            List.of());

    private static final Diff DIFF = new Diff("@@ -1 +1,2 @@\n+added",
            List.of(new Diff.ChangedFile("src/main/java/Limiter.java", java.util.Set.of(2))),
            false);

    private static final CiResult MEASURED =
            new CiResult(true, true, 0.95, 0.80, "diff coverage 95%, mutation 80%");
    private static final ReviewResult CLEAN =
            new ReviewResult(true, false, "matches the acceptance criteria");
    private static final QaResult PASSED =
            new QaResult(QaOutcome.PASS, "429 on request 101", 0, 0.36, 0);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @DisplayName("the description a person reads")
    class Body {

        @Test
        @DisplayName("an unmeasured CI result is written as an absence, not as a zero")
        void unmeasuredIsNotZero() {
            String body = PullRequestBody.of(STORY, DIFF,
                    CiResult.notRun("ci.provider=scripted"), CLEAN, PASSED);

            assertThat(body)
                    .as("\"coverage 0%\" and \"coverage unmeasured\" mean opposite things "
                            + "to a reviewer")
                    .contains("nothing measured")
                    .contains("cannot be auto-approved");
        }

        @Test
        @DisplayName("it carries the reviewer's verdict and QA's evidence, not a summary of them")
        void carriesTheEvidence() {
            String body = PullRequestBody.of(STORY, DIFF, MEASURED,
                    new ReviewResult(true, true, "Correct, but it edits application.yml"),
                    new QaResult(QaOutcome.PASS, "429 on request 101, Retry-After present",
                            2, 0.36, 0));

            assertThat(body)
                    .contains("Correct, but it edits application.yml")
                    .contains("429 on request 101, Retry-After present")
                    .contains("goes outside the ticket")
                    .as("a pull request that says only \"automated change\" trains people "
                            + "to rubber-stamp")
                    .contains("2 criteria need a person");
        }

        @Test
        @DisplayName("a verdict containing a pipe cannot break the table it sits in")
        void verdictIsEscaped() {
            String body = PullRequestBody.of(STORY, DIFF, MEASURED,
                    new ReviewResult(true, false, "fine | mostly\nacross two lines"), PASSED);

            assertThat(body).contains("fine \\| mostly across two lines");
        }

        @Test
        @DisplayName("a clean, measured story needs nobody and says so by omission")
        void cleanStoryHasNoWarning() {
            String body = PullRequestBody.of(STORY, DIFF, MEASURED, CLEAN, PASSED);

            assertThat(body).doesNotContain("cannot be auto-approved");
            assertThat(PullRequestBody.needsAPerson(MEASURED, CLEAN, PASSED)).isFalse();
        }

        @Test
        @DisplayName("anything short of clean needs a person, which makes the PR a draft")
        void anythingElseNeedsAPerson() {
            assertThat(PullRequestBody.needsAPerson(
                    CiResult.notRun("not wired"), CLEAN, PASSED)).isTrue();
            assertThat(PullRequestBody.needsAPerson(MEASURED,
                    new ReviewResult(true, true, "out of scope"), PASSED)).isTrue();
            assertThat(PullRequestBody.needsAPerson(MEASURED, CLEAN,
                    new QaResult(QaOutcome.PASS, "ok", 1, 0.0, 0))).isTrue();
        }
    }

    @Nested
    @DisplayName("against a real HTTP server")
    class OverHttp {

        @Test
        @DisplayName("opens a draft when something needs a person, and reports the url back")
        void opensADraft() throws IOException {
            AtomicReference<String> received = new AtomicReference<>();
            String base = stubGitHub(received, 201, """
                    {"number": 42, "html_url": "https://github.invalid/o/r/pull/42",
                     "draft": true}
                    """);

            PullRequest opened = new GitHubPullRequests(base, "owner/repo", "token", "main")
                    .open(STORY, "feat/loop-14-3", DIFF,
                            CiResult.notRun("not wired"), CLEAN, PASSED);

            assertThat(opened.opened()).isTrue();
            assertThat(opened.number()).isEqualTo(42);
            assertThat(opened.url()).isEqualTo("https://github.invalid/o/r/pull/42");

            var sent = new ObjectMapper().readTree(received.get());
            assertThat(sent.path("head").asText()).isEqualTo("feat/loop-14-3");
            assertThat(sent.path("base").asText()).isEqualTo("main");
            assertThat(sent.path("title").asText()).isEqualTo("LOOP-14-3: Redis-backed limiter");
            assertThat(sent.path("draft").asBoolean())
                    .as("a ready pull request is a request to merge, and asking for that "
                            + "on a change whose CI measured nothing makes review a formality")
                    .isTrue();
            assertThat(sent.path("body").asText()).contains("nothing measured");
        }

        @Test
        @DisplayName("a clean story opens a ready pull request, not a draft")
        void cleanStoryIsNotADraft() throws IOException {
            AtomicReference<String> received = new AtomicReference<>();
            String base = stubGitHub(received, 201,
                    "{\"number\": 7, \"html_url\": \"u\", \"draft\": false}");

            new GitHubPullRequests(base, "owner/repo", "token", "main")
                    .open(STORY, "feat/x", DIFF, MEASURED, CLEAN, PASSED);

            assertThat(new ObjectMapper().readTree(received.get()).path("draft").asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("a refusal comes back as a result carrying what GitHub said")
        void refusalIsAResultNotAnException() throws IOException {
            String base = stubGitHub(new AtomicReference<>(), 422, """
                    {"message": "Validation Failed",
                     "errors": [{"message": "No commits between main and feat/x"}]}
                    """);

            PullRequest result = new GitHubPullRequests(base, "owner/repo", "token", "main")
                    .open(STORY, "feat/x", DIFF, MEASURED, CLEAN, PASSED);

            assertThat(result.opened()).isFalse();
            assertThat(result.note())
                    .as("422 is usually mundane - no commits, or a pull request already "
                            + "open - and a person needs the text GitHub sent")
                    .contains("422")
                    .contains("No commits between");
        }

        @Test
        @DisplayName("a 201 with no number is not treated as a pull request")
        void emptyResponseIsNotSuccess() throws IOException {
            String base = stubGitHub(new AtomicReference<>(), 201, "{}");

            PullRequest result = new GitHubPullRequests(base, "owner/repo", "token", "main")
                    .open(STORY, "feat/x", DIFF, MEASURED, CLEAN, PASSED);

            assertThat(result.opened()).isFalse();
            assertThat(result.note()).contains("no pull request number");
        }

        @Test
        @DisplayName("no branch means no request is sent at all")
        void noBranchSendsNothing() {
            PullRequest result = new GitHubPullRequests(
                    "http://127.0.0.1:1", "owner/repo", "token", "main")
                    .open(STORY, "  ", DIFF, MEASURED, CLEAN, PASSED);

            assertThat(result.opened()).isFalse();
            assertThat(result.note()).contains("no branch");
        }

        /** A loopback HTTP server that records the body it was sent. */
        private String stubGitHub(AtomicReference<String> received, int status, String response)
                throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repos/owner/repo/pulls", exchange -> {
                received.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }
}

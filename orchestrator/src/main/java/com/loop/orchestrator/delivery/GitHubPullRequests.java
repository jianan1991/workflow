package com.loop.orchestrator.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.loop.orchestrator.domain.Domain.CiResult;
import com.loop.orchestrator.domain.Domain.PullRequest;
import com.loop.orchestrator.domain.Domain.QaResult;
import com.loop.orchestrator.domain.Domain.ReviewResult;
import com.loop.orchestrator.domain.Domain.Story;
import com.loop.orchestrator.support.TimeoutRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Opens the pull request over GitHub's REST API.
 *
 * <p>The API rather than the {@code gh} CLI, which was the first instinct. Two reasons, and
 * the second is the one that decided it: there is one fewer binary to install in the
 * container, and an HTTP client can be pointed at a local stub in a test. The CLI path
 * would have been another component that could only be verified by running it against the
 * real thing — this project already has one of those in the dev agent and does not need a
 * second.
 */
public class GitHubPullRequests implements PullRequests {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequests.class);

    private final RestClient http;
    private final String repository;
    private final String baseBranch;

    /**
     * @param repository {@code owner/name}
     * @param token      a fine-grained token with pull-request write on that one repository
     *                   and nothing else. It is the second credential in the system after
     *                   the model key, and the only one that can change anything outside
     *                   this process.
     */
    public GitHubPullRequests(String apiBaseUrl, String repository, String token,
                              String baseBranch) {
        this.repository = repository;
        this.baseBranch = baseBranch;
        this.http = TimeoutRestClient.createWithBearer(apiBaseUrl, token,
                Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    @Override
    public PullRequest open(Story story, String branch, Diff diff,
                            CiResult ci, ReviewResult review, QaResult qa) {

        if (branch == null || branch.isBlank()) {
            return PullRequest.notOpened("no branch to open a pull request from");
        }

        String body = PullRequestBody.of(story, diff, ci, review, qa);
        String title = PullRequestBody.title(story);

        try {
            JsonNode created = http.post()
                    .uri("/repos/{repo}/pulls", repository)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(Map.of(
                            "title", title,
                            "head", branch,
                            "base", baseBranch,
                            "body", body,
                            // Always a draft when something needs a person. A ready pull
                            // request is a request to merge, and asking for that on a
                            // change whose CI measured nothing is how review becomes a
                            // formality.
                            "draft", PullRequestBody.needsAPerson(ci, review, qa)))
                    .retrieve()
                    .body(JsonNode.class);

            if (created == null || !created.has("number")) {
                return PullRequest.notOpened(
                        "GitHub accepted the request but returned no pull request number");
            }

            int number = created.path("number").asInt();
            String url = created.path("html_url").asText("");
            log.info("[pr] {} -> #{} {}", story.id(), number, url);

            return new PullRequest(number, url, true,
                    "opened against " + baseBranch
                            + (created.path("draft").asBoolean(false) ? " as a draft" : ""));

        } catch (RestClientResponseException e) {
            // 422 is the common one and it is usually mundane: no commits between the
            // branches, or a pull request already open for this branch. Both need the text
            // GitHub sent, not a generic failure.
            log.warn("[pr] {} could not be opened: {} {}", story.id(),
                    e.getStatusCode().value(), e.getResponseBodyAsString());

            return PullRequest.notOpened("GitHub refused the pull request with %d: %s"
                    .formatted(e.getStatusCode().value(), firstError(e)));

        } catch (ResourceAccessException e) {
            return PullRequest.notOpened(
                    "no reply from GitHub within 30s: " + e.getMessage());
        }
    }

    /** GitHub nests the useful part; the envelope on its own says nothing. */
    private static String firstError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    @Override
    public String describe() {
        return "github pull requests on " + repository + " against " + baseBranch
                + " — UNVERIFIED against a real repository";
    }

    /** Exposed so a test can assert on the body without reaching GitHub. */
    static List<String> requiredScopes() {
        return List.of("pull_requests:write", "contents:read");
    }
}

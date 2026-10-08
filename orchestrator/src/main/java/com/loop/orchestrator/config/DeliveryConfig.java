package com.loop.orchestrator.config;

import com.loop.orchestrator.delivery.BuildRunner;
import com.loop.orchestrator.delivery.GitHubPullRequests;
import com.loop.orchestrator.delivery.GitSourceControl;
import com.loop.orchestrator.delivery.MavenBuildRunner;
import com.loop.orchestrator.delivery.PullRequests;
import com.loop.orchestrator.delivery.ScriptedBuildRunner;
import com.loop.orchestrator.delivery.ScriptedPullRequests;
import com.loop.orchestrator.delivery.ScriptedSourceControl;
import com.loop.orchestrator.delivery.SourceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the three steps between an agent's branch and a pull request a person can read.
 *
 * <p>All three default to scripted, and all three refuse to start when switched on without
 * what they need. That is the same rule the dev agent follows and it exists because of the
 * same failure: the expensive mistake here is not a crash on startup, it is a pipeline that
 * looks like it is working. A source control seam that quietly reads the wrong checkout, a
 * build runner that reports a pass it did not measure, or a pull request opener pointed at
 * the wrong repository all produce confident output that nobody can tell from the real
 * thing until something merges.
 */
@Configuration
public class DeliveryConfig {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfig.class);

    @Bean
    public SourceControl sourceControl(
            @Value("${scm.provider:scripted}") String provider,
            @Value("${scm.executable:git}") String executable,
            @Value("${dev.workspace:}") String workspace,
            @Value("${scm.base-branch:main}") String baseBranch) {

        if (!"git".equalsIgnoreCase(provider)) {
            log.warn("scm.provider=scripted ''—'' NO REPOSITORY WILL BE READ. The reviewer will "
                    + "be told there is no diff, which is true, and its verdict will say so.");
            return new ScriptedSourceControl();
        }

        if (workspace.isBlank()) {
            throw new IllegalStateException("scm.provider=git needs dev.workspace set to the "
                    + "checkout the dev agent works in. Without it git would run in whatever "
                    + "directory this process happens to be in and report a diff of the "
                    + "wrong repository.");
        }

        SourceControl scm = new GitSourceControl(executable, Path.of(workspace), baseBranch);
        log.info("scm.provider=git ''—'' {}", scm.describe());
        return scm;
    }

    @Bean
    public BuildRunner buildRunner(
            @Value("${ci.provider:scripted}") String provider,
            @Value("${ci.executable:mvn}") String executable,
            @Value("${dev.workspace:}") String workspace,
            @Value("${ci.module:.}") String module,
            @Value("${ci.timeout-minutes:25}") int timeoutMinutes,
            @Value("${ci.diff-coverage-threshold:0.90}") double diffCoverage,
            @Value("${ci.mutation-threshold:0.70}") double mutation,
            SourceControl scm) {

        if (!"maven".equalsIgnoreCase(provider)) {
            log.warn("ci.provider=scripted ''—'' NO BUILD WILL RUN. Coverage and mutation stay "
                    + "unmeasured, so no story can auto-approve and every one goes to a "
                    + "person. That is the correct behaviour, not a workaround.");
            return new ScriptedBuildRunner();
        }

        if (workspace.isBlank()) {
            throw new IllegalStateException("ci.provider=maven needs dev.workspace set to the "
                    + "repository whose build should run.");
        }
        if (timeoutMinutes >= 30) {
            // The activity's start-to-close timeout is 30 minutes. A build allowed to run
            // longer gets abandoned mid-run and retried on a half-built workspace.
            throw new IllegalStateException("ci.timeout-minutes must stay under the 30 "
                    + "minute activity timeout in StoryWorkflowImpl, got " + timeoutMinutes);
        }

        BuildRunner runner = new MavenBuildRunner(executable, Path.of(workspace), module,
                Duration.ofMinutes(timeoutMinutes), diffCoverage, mutation, scm);
        log.info("ci.provider=maven ''—'' {}", runner.describe());
        return runner;
    }

    @Bean
    public PullRequests pullRequests(
            @Value("${pr.provider:scripted}") String provider,
            @Value("${pr.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${pr.repository:}") String repository,
            @Value("${pr.token:}") String token,
            @Value("${scm.base-branch:main}") String baseBranch) {

        if (!"github".equalsIgnoreCase(provider)) {
            log.warn("pr.provider=scripted ''—'' NOTHING WILL BE OPENED. The description that "
                    + "would have been written is logged, so the wording can be judged "
                    + "before any of it is public.");
            return new ScriptedPullRequests();
        }

        if (repository.isBlank() || !repository.contains("/")) {
            throw new IllegalStateException("pr.provider=github needs pr.repository as "
                    + "owner/name, got '" + repository + "'");
        }
        if (token.isBlank()) {
            throw new IllegalStateException("pr.provider=github needs pr.token. Use a "
                    + "fine-grained token scoped to that one repository with pull-request "
                    + "write and nothing else ''—'' it is the only credential in this system "
                    + "that can change anything outside this process.");
        }

        PullRequests pullRequests =
                new GitHubPullRequests(apiBaseUrl, repository, token, baseBranch);
        log.warn("pr.provider=github ''—'' {}. This pipeline can now open pull requests that "
                + "other people see.", pullRequests.describe());
        return pullRequests;
    }
}

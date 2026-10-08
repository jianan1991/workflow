package com.loop.orchestrator.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two numbers the auto-approval gate is allowed to believe.
 *
 * <p>The fixtures are the real report shapes, cut down: JaCoCo's {@code <line nr ci mi cb
 * mb>} elements under {@code <sourcefile>} under {@code <package>}, and PIT's
 * {@code <mutation detected status>} with a nested {@code <mutatedClass>} and
 * {@code <lineNumber>}. Both parsers were also run against this repository's own full
 * reports — see the verification log in PHASES.md.
 */
class ReportParsingTest {

    private static final String JACOCO = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="loop-orchestrator">
              <package name="com/loop/orchestrator/delivery">
                <sourcefile name="Limiter.java">
                  <line nr="10" mi="0" ci="4" mb="0" cb="0"/>
                  <line nr="11" mi="3" ci="0" mb="0" cb="0"/>
                  <line nr="12" mi="0" ci="0" mb="0" cb="0"/>
                  <line nr="13" mi="0" ci="7" mb="1" cb="1"/>
                </sourcefile>
              </package>
            </report>
            """;

    private static final String PIT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mutations partial="false">
              <mutation detected="true" status="KILLED">
                <sourceFile>Limiter.java</sourceFile>
                <mutatedClass>com.loop.orchestrator.delivery.Limiter</mutatedClass>
                <lineNumber>10</lineNumber>
              </mutation>
              <mutation detected="false" status="SURVIVED">
                <sourceFile>Limiter.java</sourceFile>
                <mutatedClass>com.loop.orchestrator.delivery.Limiter</mutatedClass>
                <lineNumber>13</lineNumber>
              </mutation>
              <mutation detected="false" status="NON_VIABLE">
                <sourceFile>Limiter.java</sourceFile>
                <mutatedClass>com.loop.orchestrator.delivery.Limiter$Bucket</mutatedClass>
                <lineNumber>13</lineNumber>
              </mutation>
              <mutation detected="true" status="KILLED">
                <sourceFile>Other.java</sourceFile>
                <mutatedClass>com.loop.orchestrator.delivery.Other</mutatedClass>
                <lineNumber>99</lineNumber>
              </mutation>
            </mutations>
            """;

    private static Diff diffTouching(Set<Integer> lines) {
        return new Diff("", List.of(new Diff.ChangedFile(
                "orchestrator/src/main/java/com/loop/orchestrator/delivery/Limiter.java",
                lines)), false);
    }

    @Nested
    @DisplayName("diff coverage")
    class Coverage {

        @Test
        @DisplayName("counts only the changed lines, and only the executable ones")
        void onlyChangedExecutableLines() {
            // 10 is covered, 11 is missed, 12 is not executable at all (a comment or a
            // blank line), 13 is covered. Line 99 is not in the report.
            JacocoReport.Measurement measured = JacocoReport.parse(bytes(JACOCO))
                    .diffCoverage(diffTouching(Set.of(10, 11, 12, 13, 99)));

            assertThat(measured.executableLines())
                    .as("a long licence header would otherwise read as untested code")
                    .isEqualTo(3);
            assertThat(measured.coveredLines()).isEqualTo(2);
            assertThat(measured.ratio()).isCloseTo(2.0 / 3.0, within());
        }

        @Test
        @DisplayName("a change with nothing executable in it has no coverage to have")
        void nothingExecutable() {
            JacocoReport.Measurement measured = JacocoReport.parse(bytes(JACOCO))
                    .diffCoverage(diffTouching(Set.of(12)));

            assertThat(measured.hasAnything())
                    .as("a documentation-only change must not fail a coverage gate")
                    .isFalse();
            assertThat(measured.ratio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the repository path is matched against the report's package path")
        void pathsAreJoinedOnTheSuffix() {
            // The diff says orchestrator/src/main/java/...; the report says com/loop/...
            // Neither side should have to know the module layout.
            JacocoReport.Measurement measured = JacocoReport.parse(bytes(JACOCO))
                    .diffCoverage(diffTouching(Set.of(10)));

            assertThat(measured.executableLines()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unreadable report throws rather than reporting zero")
        void unreadableReportThrows() {
            assertThatThrownBy(() -> JacocoReport.parse(bytes("not xml at all")))
                    .as("zero coverage reads as a tested-and-failing change; the caller "
                            + "turns a throw into notRun, which cannot pass")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JaCoCo");
        }
    }

    @Nested
    @DisplayName("mutation score")
    class Mutation {

        @Test
        @DisplayName("scored over the changed lines, excluding mutations the JVM rejected")
        void scopedToTheDiff() {
            PitReport.Score score = PitReport.parse(bytes(PIT))
                    .forDiff(diffTouching(Set.of(10, 13)));

            assertThat(score.considered())
                    .as("the NON_VIABLE one says nothing about the tests, and PIT leaves "
                            + "it out of its own score too")
                    .isEqualTo(2);
            assertThat(score.detected()).isEqualTo(1);
            assertThat(score.ratio()).isCloseTo(0.5, within());
        }

        @Test
        @DisplayName("a mutation in another file is not counted against this change")
        void otherFilesExcluded() {
            PitReport.Score score = PitReport.parse(bytes(PIT))
                    .forDiff(diffTouching(Set.of(10)));

            assertThat(score.considered()).isEqualTo(1);
            assertThat(score.detected()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing landing on the changed lines is not a score of zero")
        void nothingOnTheDiff() {
            PitReport.Score score = PitReport.parse(bytes(PIT))
                    .forDiff(diffTouching(Set.of(500)));

            assertThat(score.hasAnything()).isFalse();
            assertThat(score.ratio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the whole-run score is still available when the diff has nothing")
        void overallScore() {
            PitReport.Score score = PitReport.parse(bytes(PIT)).overall();

            assertThat(score.considered()).isEqualTo(3);
            assertThat(score.detected()).isEqualTo(2);
        }
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.0001);
    }
}

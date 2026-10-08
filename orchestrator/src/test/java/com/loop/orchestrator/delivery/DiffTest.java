package com.loop.orchestrator.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a patch into the form the coverage gate needs.
 *
 * <p>Worth testing precisely, because every number the gate reports is derived from it. A
 * parser that counted context lines would quietly lower diff coverage on well-covered files
 * and raise it on badly-covered ones — a gate moving in the wrong direction, with no
 * symptom except decisions nobody can explain.
 */
class DiffTest {

    @Nested
    @DisplayName("line numbers come from the hunk body, not the header")
    class LineNumbers {

        @Test
        @DisplayName("only added lines count, and they are numbered on the new side")
        void addedLinesOnly() {
            String patch = """
                    diff --git a/src/main/java/Limiter.java b/src/main/java/Limiter.java
                    index 1111111..2222222 100644
                    --- a/src/main/java/Limiter.java
                    +++ b/src/main/java/Limiter.java
                    @@ -10,4 +10,6 @@ public class Limiter {
                     public class Limiter {

                    +    boolean allow(String tenant) {
                    +        return true;
                    +    }
                     }
                    """;

            List<Diff.ChangedFile> files = UnifiedDiff.parse(patch);

            assertThat(files).hasSize(1);
            assertThat(files.getFirst().path()).isEqualTo("src/main/java/Limiter.java");
            assertThat(files.getFirst().changedLines())
                    .as("the hunk starts at 10; two context lines precede the three "
                            + "additions, so they are 12, 13 and 14")
                    .containsExactlyInAnyOrder(12, 13, 14);
        }

        @Test
        @DisplayName("a deleted line advances nothing, because it has no line on the new side")
        void deletionsDoNotAdvance() {
            String patch = """
                    --- a/A.java
                    +++ b/A.java
                    @@ -1,4 +1,3 @@
                     one
                    -two
                    -three
                    +replacement
                     four
                    """;

            List<Diff.ChangedFile> files = UnifiedDiff.parse(patch);

            assertThat(files.getFirst().changedLines())
                    .as("counting removed lines would make every deletion look untested")
                    .containsExactly(2);
        }

        @Test
        @DisplayName("several files in one patch are kept apart")
        void multipleFiles() {
            String patch = """
                    --- a/A.java
                    +++ b/A.java
                    @@ -1,1 +1,2 @@
                     a
                    +added to A
                    --- a/b/B.java
                    +++ b/b/B.java
                    @@ -5,1 +5,2 @@
                     b
                    +added to B
                    """;

            List<Diff.ChangedFile> files = UnifiedDiff.parse(patch);

            assertThat(files).hasSize(2);
            assertThat(files.get(0).changedLines()).containsExactly(2);
            assertThat(files.get(1).path()).isEqualTo("b/B.java");
            assertThat(files.get(1).changedLines()).containsExactly(6);
        }

        @Test
        @DisplayName("a deleted file contributes nothing rather than a /dev/null entry")
        void deletedFileIsSkipped() {
            String patch = """
                    --- a/Gone.java
                    +++ /dev/null
                    @@ -1,2 +0,0 @@
                    -one
                    -two
                    """;

            assertThat(UnifiedDiff.parse(patch)).isEmpty();
        }

        @Test
        @DisplayName("an empty patch parses to nothing instead of throwing")
        void emptyPatch() {
            assertThat(UnifiedDiff.parse("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("what the mutation run gets scoped to")
    class ChangedClasses {

        @Test
        @DisplayName("main sources become class names; tests and resources do not")
        void onlyMainSources() {
            Diff diff = new Diff("", List.of(
                    new Diff.ChangedFile("orchestrator/src/main/java/com/loop/A.java", java.util.Set.of(1)),
                    new Diff.ChangedFile("orchestrator/src/test/java/com/loop/ATest.java", java.util.Set.of(1)),
                    new Diff.ChangedFile("README.md", java.util.Set.of(1))), false);

            assertThat(diff.changedMainClasses())
                    .as("mutating a test class proves nothing, and PIT over a whole "
                            + "repository takes long enough that nobody runs it")
                    .containsExactly("com.loop.A");
            assertThat(diff.javaFiles()).hasSize(2);
        }

        @Test
        @DisplayName("a change with no Java at all has nothing to mutate")
        void noJava() {
            Diff diff = new Diff("", List.of(
                    new Diff.ChangedFile("docs/PHASES.md", java.util.Set.of(3))), false);

            assertThat(diff.changedMainClasses()).isEmpty();
            assertThat(diff.changedLineCount()).isEqualTo(1);
        }
    }
}

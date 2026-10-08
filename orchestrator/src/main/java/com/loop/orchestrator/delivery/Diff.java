package com.loop.orchestrator.delivery;

import java.util.List;
import java.util.Set;

/**
 * What the dev agent actually changed.
 *
 * <p>Two readers with different needs, which is why both forms are here. The reviewer agent
 * reads {@code unified} — the patch as a person would read it, context and all. The CI gate
 * reads {@code files}, because diff coverage is a question about specific line numbers: of
 * the lines this change added, how many did a test execute?
 *
 * <p>Before this existed the reviewer was handed the literal string "(no diff — the dev
 * agent is not wired)" and asked whether it satisfied the acceptance criteria. It said no,
 * correctly, forever.
 *
 * @param truncated whether {@code unified} is the whole patch. A 4000-line diff would push
 *                  the acceptance criteria out of the reviewer's context, and a reviewer
 *                  silently judging half a change is worse than one told it has half.
 */
public record Diff(String unified, List<ChangedFile> files, boolean truncated) {

    /**
     * @param changedLines line numbers on the <b>new</b> side that this change added or
     *                     rewrote. Deleted lines are deliberately absent: no test can
     *                     execute a line that is gone, so counting them would make every
     *                     deletion look like a coverage failure.
     */
    public record ChangedFile(String path, Set<Integer> changedLines) {
    }

    public static Diff empty(String why) {
        return new Diff(why, List.of(), false);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public int changedLineCount() {
        return files.stream().mapToInt(file -> file.changedLines().size()).sum();
    }

    /** Only the Java sources. Coverage and mutation have nothing to say about anything else. */
    public List<ChangedFile> javaFiles() {
        return files.stream().filter(file -> file.path().endsWith(".java")).toList();
    }

    /**
     * Fully qualified names of the main-source classes this change touched, for scoping the
     * mutation run.
     *
     * <p>Scoped on purpose: PIT over a whole project takes long enough that nobody runs it,
     * and the number that matters is whether <b>this change</b> is tested, not whether the
     * repository is.
     */
    public List<String> changedMainClasses() {
        return javaFiles().stream()
                .map(ChangedFile::path)
                .filter(path -> path.contains("src/main/java/"))
                .map(path -> path.substring(path.indexOf("src/main/java/") + "src/main/java/".length()))
                .map(path -> path.substring(0, path.length() - ".java".length()).replace('/', '.'))
                .distinct()
                .toList();
    }
}

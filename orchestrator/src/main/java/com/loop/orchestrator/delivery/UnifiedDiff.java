package com.loop.orchestrator.delivery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a unified diff into the file-and-line form the coverage gate needs.
 *
 * <p>Parsed from the hunk bodies rather than the hunk headers. A header like
 * {@code @@ -12,7 +12,9 @@} describes a range that includes unchanged context lines, so
 * taking it at face value counts lines the change never touched — which would quietly
 * lower diff coverage every time an agent edited a well-covered file and raise it every
 * time it edited a badly-covered one. Walking the body costs nothing and is exact.
 */
final class UnifiedDiff {

    private UnifiedDiff() {
    }

    static List<Diff.ChangedFile> parse(String unified) {
        Map<String, Set<Integer>> byFile = new LinkedHashMap<>();

        String path = null;
        int newLine = 0;

        for (String line : unified.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                // "+++ b/src/main/java/Foo.java", or "+++ /dev/null" for a deletion.
                String target = line.substring(4).trim();
                path = "/dev/null".equals(target) ? null : stripPrefix(target);
                continue;
            }
            if (line.startsWith("@@")) {
                newLine = startOfHunk(line);
                continue;
            }
            if (path == null || newLine == 0) {
                continue;
            }

            if (line.startsWith("+")) {
                byFile.computeIfAbsent(path, key -> new LinkedHashSet<>()).add(newLine);
                newLine++;
            } else if (line.startsWith("-")) {
                // Gone from the new side, so it has no line number there and nothing can
                // execute it. Do not advance.
                continue;
            } else if (line.startsWith(" ") || line.isEmpty()) {
                newLine++;
            }
            // "\ No newline at end of file" and anything else: not a line of the file.
        }

        List<Diff.ChangedFile> files = new ArrayList<>();
        byFile.forEach((file, lines) -> files.add(new Diff.ChangedFile(file, Set.copyOf(lines))));
        return List.copyOf(files);
    }

    /** {@code @@ -12,7 +40,9 @@ void method()} -> 40. */
    private static int startOfHunk(String header) {
        int plus = header.indexOf('+');
        if (plus < 0) {
            return 0;
        }
        int end = plus + 1;
        while (end < header.length() && Character.isDigit(header.charAt(end))) {
            end++;
        }
        if (end == plus + 1) {
            return 0;
        }
        return Integer.parseInt(header.substring(plus + 1, end));
    }

    /** Git writes {@code a/} and {@code b/} prefixes; the repository does not have them. */
    private static String stripPrefix(String target) {
        if (target.startsWith("a/") || target.startsWith("b/")) {
            return target.substring(2);
        }
        return target;
    }
}

package com.loop.orchestrator.knowledge;

import com.loop.orchestrator.knowledge.Knowledge.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a knowledge-base note into one chunk per section.
 *
 * <p>Sections, not fixed-size windows: a decision and its reasoning live under one
 * heading, and cutting every 500 characters separates the ruling from the reason it was
 * made — which is the half an agent actually needs.
 */
public class MarkdownChunker {

    /** Sections shorter than this are folded into the next one rather than indexed alone. */
    private static final int MIN_SECTION_CHARS = 40;

    public List<Chunk> chunk(String file, String markdown) {
        Frontmatter front = Frontmatter.parse(markdown);

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String heading = "";
        StringBuilder pending = new StringBuilder();

        for (String line : front.body().split("\n", -1)) {
            if (isSectionHeading(line)) {
                addIfSubstantial(chunks, file, heading, body, pending, front);
                heading = line.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            body.append(line).append('\n');
        }
        addIfSubstantial(chunks, file, heading, body, pending, front);

        // A trailing fragment too small to stand alone still belongs somewhere.
        if (!pending.isEmpty() && !chunks.isEmpty()) {
            Chunk last = chunks.getLast();
            chunks.set(chunks.size() - 1, new Chunk(last.id(), last.file(), last.heading(),
                    last.text() + "\n" + pending.toString().trim(), last.date(), last.author()));
        }
        return chunks;
    }

    private void addIfSubstantial(List<Chunk> chunks, String file, String heading,
                                  StringBuilder body, StringBuilder pending, Frontmatter front) {
        String text = body.toString().trim();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }

        if (text.length() < MIN_SECTION_CHARS) {
            pending.append(heading.isBlank() ? "" : heading + ": ").append(text).append('\n');
            return;
        }

        if (!pending.isEmpty()) {
            text = pending.toString().trim() + "\n" + text;
            pending.setLength(0);
        }

        chunks.add(new Chunk(idFor(file, heading), file, heading, text,
                front.date(), front.author()));
    }

    private static boolean isSectionHeading(String line) {
        return line.startsWith("## ") || line.startsWith("# ") || line.startsWith("### ");
    }

    /**
     * Stable across runs, so editing a note updates its entry instead of leaving the old
     * text in the index beside the new.
     */
    static String idFor(String file, String heading) {
        return UUID.nameUUIDFromBytes((file + "#" + heading).getBytes()).toString();
    }

    /** The {@code --- date: … author: … ---} block every note is expected to carry. */
    record Frontmatter(String date, String author, String body) {

        static Frontmatter parse(String markdown) {
            String normalised = markdown.replace("\r\n", "\n");
            if (!normalised.startsWith("---\n")) {
                return new Frontmatter("undated", "unattributed", normalised);
            }
            int end = normalised.indexOf("\n---", 4);
            if (end < 0) {
                return new Frontmatter("undated", "unattributed", normalised);
            }

            String date = "undated";
            String author = "unattributed";
            for (String line : normalised.substring(4, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                if (key.equals("date")) {
                    date = value;
                } else if (key.equals("author") || key.equals("decided-by")) {
                    author = value;
                }
            }
            String body = normalised.substring(Math.min(end + 4, normalised.length()));
            return new Frontmatter(date, author, body);
        }
    }
}

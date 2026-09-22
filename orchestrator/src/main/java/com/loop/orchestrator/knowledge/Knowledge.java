package com.loop.orchestrator.knowledge;

import java.util.List;

/** Shapes the retrieval layer passes around. */
public final class Knowledge {

    private Knowledge() {
    }

    /**
     * One indexable piece of the knowledge base — normally one section under one heading.
     *
     * @param date   when the decision was made. Every entry is dated and attributed,
     *               because nothing else in this system detects that a past answer was
     *               wrong: a hasty Friday reply otherwise becomes gospel, cited
     *               confidently forever.
     * @param author who decided it, for the same reason
     */
    public record Chunk(String id,
                        String file,
                        String heading,
                        String text,
                        String date,
                        String author) {

        /** What actually gets embedded. The heading carries real signal — keep it. */
        public String embeddable() {
            return heading.isBlank() ? text : heading + "\n\n" + text;
        }
    }

    public record Hit(Chunk chunk, double score) {
    }

    /**
     * The result of an agent trying to answer its own question from the knowledge base.
     *
     * @param queriesTried every reformulation the agent attempted. Surfacing this is what
     *                     separates a real gap in the knowledge base from an agent that
     *                     gave up early, and it is the only honest signal of whether
     *                     retrieval is any good.
     */
    public record SearchOutcome(boolean sufficient,
                                List<Hit> hits,
                                List<String> queriesTried,
                                int attemptsAllowed) {
    }
}

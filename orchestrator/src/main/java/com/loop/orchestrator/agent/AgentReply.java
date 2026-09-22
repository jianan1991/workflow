package com.loop.orchestrator.agent;

/** Shared handling for the ways a model's reply is almost, but not quite, what was asked for. */
final class AgentReply {

    private AgentReply() {
    }

    /**
     * Models wrap JSON in a code fence despite being told not to. Cheaper to strip it than
     * to argue with the prompt.
     */
    static String stripFence(String reply) {
        String trimmed = reply == null ? "" : reply.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    /** Enough of the reply to debug from the log, without pasting a whole context window. */
    static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

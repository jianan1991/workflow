package com.loop.orchestrator.agent;

/**
 * A model replied, but with something that cannot be acted on.
 *
 * <p>Always thrown rather than worked around. The tempting alternative — defaulting a
 * missing field, skipping a malformed entry — produces a half-built journey or a story
 * list with a hole in it, and the pipeline carries on confidently from there. A loud
 * failure costs one retry; a quiet one costs a wrong feature built and tested.
 *
 * <p>Carries the agent and the thing it was working on, so the log alone is enough to
 * debug from.
 */
public class AgentReplyException extends RuntimeException {

    private final String agent;

    public AgentReplyException(String agent, String subject, String detail, Throwable cause) {
        super("[%s] unusable reply for %s — %s".formatted(agent, subject, detail), cause);
        this.agent = agent;
    }

    public String agent() {
        return agent;
    }
}

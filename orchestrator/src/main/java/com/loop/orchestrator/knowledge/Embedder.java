package com.loop.orchestrator.knowledge;

public interface Embedder {

    int dimensions();

    float[] embed(String text);

    /** Name shown in logs and on the retrieval screen, so it is never a mystery which ran. */
    String name();
}

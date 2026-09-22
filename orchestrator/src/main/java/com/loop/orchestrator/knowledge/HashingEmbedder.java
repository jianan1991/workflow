package com.loop.orchestrator.knowledge;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deterministic, offline embedder: hashed bag-of-words with sublinear term weighting,
 * L2-normalised so cosine similarity behaves.
 *
 * <p><b>What it is for.</b> Proving the retrieval plumbing end to end — chunking, storage,
 * search, the attempt cap, the question that gets raised when nothing is found — without a
 * network call, an API key, or a running Qdrant. Every phase of this pipeline should be
 * testable on a laptop with the wifi off, and retrieval is the one most likely to be
 * skipped otherwise.
 *
 * <p><b>What it is not.</b> Semantic. It matches on shared words, so "throttling" and
 * "rate limiting" look unrelated to it. Good enough to prove a query reaches the right
 * note in a seeded knowledge base; useless for judging retrieval <em>quality</em>. Switch
 * to a real embedding model before drawing any conclusion about whether agents can find
 * what they need.
 */
public class HashingEmbedder implements Embedder {

    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9]+");

    /** Words frequent enough to drown the signal in a knowledge base this small. */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were",
            "it", "its", "this", "that", "for", "on", "with", "as", "by", "be", "not",
            "we", "you", "they", "but", "if", "so", "at", "from", "than", "then");

    private final int dimensions;

    public HashingEmbedder(int dimensions) {
        if (dimensions < 16) {
            throw new IllegalArgumentException("dimensions must be at least 16, got " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String name() {
        return "hashing-" + dimensions + " (offline, lexical only)";
    }

    @Override
    public float[] embed(String text) {
        float[] counts = new float[dimensions];

        for (String token : TOKEN.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() < 3 || STOP.contains(token)) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), dimensions);
            // A sign bit drawn from a second hash keeps unrelated words that collide in
            // the same bucket from always reinforcing each other.
            counts[bucket] += Math.floorMod(token.hashCode() >>> 16, 2) == 0 ? 1f : -1f;
        }

        // Sublinear weighting: a word used nine times is not nine times as informative.
        for (int i = 0; i < dimensions; i++) {
            float c = counts[i];
            counts[i] = c == 0f ? 0f : Math.signum(c) * (float) (1 + Math.log(Math.abs(c)));
        }

        return normalise(counts);
    }

    private static float[] normalise(float[] v) {
        double sumOfSquares = 0;
        for (float f : v) {
            sumOfSquares += (double) f * f;
        }
        if (sumOfSquares == 0) {
            // An empty or all-stopword document. Returning zeros keeps cosine at 0
            // against everything, which is the honest answer.
            return v;
        }
        float norm = (float) Math.sqrt(sumOfSquares);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }
}

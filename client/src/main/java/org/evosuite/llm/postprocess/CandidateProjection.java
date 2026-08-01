/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.llm.postprocess;

/**
 * The single assertion view used when publishing a candidate and when
 * calculating its duplicate key.
 */
final class CandidateProjection {

    private final LlmPostProcessingResponse.AssertionKind kind;
    private final String expected;
    private final String actual;
    private final String delta;

    CandidateProjection(LlmPostProcessingResponse.AssertionKind kind,
                        String expected, String actual, String delta) {
        this.kind = kind;
        this.expected = expected;
        this.actual = actual;
        this.delta = delta;
    }

    LlmPostProcessingResponse.AssertionKind getKind() {
        return kind;
    }

    String getExpected() {
        return expected;
    }

    String getActual() {
        return actual;
    }

    String getDelta() {
        return delta;
    }

    String canonicalKey() {
        return kind.name()
                + "|" + normalize(expected)
                + "|" + normalize(actual)
                + "|" + normalize(delta);
    }

    private static String normalize(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }
}

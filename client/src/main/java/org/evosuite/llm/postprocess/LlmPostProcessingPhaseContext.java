/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm.postprocess;

/**
 * Immutable configuration and isolated lifecycle state for one post-processing
 * phase. The public shell lets the suite generator carry the phase explicitly;
 * its contents are consumed only by the post-processing package.
 */
public final class LlmPostProcessingPhaseContext {

    private final PostProcessingOptions options;
    private final PostProcessingSession session;
    private final LlmPostProcessor.PhaseClock phaseClock;
    private final PostProcessingTelemetry telemetry;
    private final long startMillis;

    LlmPostProcessingPhaseContext(PostProcessingOptions options,
                                  PostProcessingSession session,
                                  LlmPostProcessor.PhaseClock phaseClock,
                                  PostProcessingTelemetry telemetry) {
        if (options == null) {
            throw new IllegalArgumentException("Post-processing options must not be null");
        }
        this.options = options;
        this.session = session == null ? new PostProcessingSession() : session;
        this.phaseClock = phaseClock;
        this.telemetry = telemetry;
        this.startMillis = phaseClock.currentTimeMillis();
    }

    PostProcessingOptions options() {
        return options;
    }

    PostProcessingSession session() {
        return session;
    }

    LlmPostProcessor.PhaseClock phaseClock() {
        return phaseClock;
    }

    PostProcessingTelemetry telemetry() {
        return telemetry;
    }

    long startMillis() {
        return startMillis;
    }
}

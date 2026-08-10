/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.testcase.TestCase;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
    private volatile String terminalReason = "";
    private final Set<TestCase> attemptedTests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TestCase> failedAssertionTests =
            Collections.newSetFromMap(new IdentityHashMap<>());

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

    void recordTerminalReason(String reason) {
        terminalReason = reason == null ? "" : reason;
    }

    /**
     * Describes why the phase stopped or was skipped. An empty value means the
     * configured work completed normally.
     */
    public String getTerminalReason() {
        return terminalReason;
    }

    /**
     * Whether ordinary EvoSuite assertions should cover tests that received no
     * oracle because the LLM phase could not run or finish for an
     * infrastructure reason. Deliberate size/call caps are not failures.
     */
    public boolean requiresStandardAssertionFallback() {
        if (!options.features().assertions()) {
            return false;
        }
        switch (terminalReason) {
            case "disabled":
            case "no_provider":
            case "no_features_enabled":
            case "service_unavailable_or_no_budget":
            case "incomplete_minimization":
            case "timeout":
            case "low_memory":
            case "budget_exhausted":
            case "infrastructure_failure":
            case "phase_failure":
                return true;
            default:
                return terminalReason.startsWith("incomplete_minimization_");
        }
    }

    void recordAttemptedTest(TestCase test) {
        if (test != null) {
            attemptedTests.add(test);
        }
    }

    void recordFailedAssertionTest(TestCase test) {
        if (test != null) {
            failedAssertionTests.add(test);
        }
    }

    /**
     * Selects only tests the failed phase did not reach, plus an attempted test
     * whose oracle-specific work failed. This preserves an explicit LLM
     * {@code NO_SAFE_ORACLE} decision on successfully processed tests.
     */
    public boolean shouldGenerateStandardAssertionsFor(TestCase test) {
        return requiresStandardAssertionFallback()
                && test != null
                && (!attemptedTests.contains(test) || failedAssertionTests.contains(test));
    }
}

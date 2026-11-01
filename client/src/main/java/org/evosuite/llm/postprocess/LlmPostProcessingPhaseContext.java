/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.llm.PhaseDeadline;
import org.evosuite.testcase.TestCase;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Immutable configuration and isolated lifecycle state for one post-processing
 * phase. The public shell lets the suite generator carry the phase explicitly;
 * its contents are consumed only by the post-processing package.
 */
public final class LlmPostProcessingPhaseContext {

    private final PostProcessingOptions options;
    private final PostProcessingSession session;
    private final LlmPostProcessor.PhaseClock phaseClock;
    private final LlmPostProcessor.ResourceGuard resourceGuard;
    private final long startMillis;
    private volatile String terminalReason = "";
    private final Set<TestCase> attemptedTests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<TestCase> failedAssertionTests =
            Collections.newSetFromMap(new IdentityHashMap<>());

    LlmPostProcessingPhaseContext(PostProcessingOptions options,
                                  PostProcessingSession session,
                                  LlmPostProcessor.PhaseClock phaseClock,
                                  LlmPostProcessor.ResourceGuard resourceGuard) {
        if (options == null) {
            throw new IllegalArgumentException("Post-processing options must not be null");
        }
        this.options = options;
        this.session = session == null ? new PostProcessingSession() : session;
        this.phaseClock = phaseClock;
        this.resourceGuard = resourceGuard;
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

    long startMillis() {
        return startMillis;
    }

    boolean isLowMemory() {
        return resourceGuard.isLowMemory(options.minimumFreeMemoryBytes());
    }

    boolean isPhaseTimedOut() {
        int timeoutSeconds = options.phaseBudget().timeoutSeconds();
        return timeoutSeconds > 0 && elapsedMillis() >= timeoutSeconds * 1000L;
    }

    boolean canStartAnotherLlmCall() {
        int timeoutSeconds = options.phaseBudget().timeoutSeconds();
        if (timeoutSeconds <= 0) {
            return true;
        }
        long remainingMillis = timeoutSeconds * 1000L - elapsedMillis();
        long callWindowMillis = Math.max(1000L, options.callTimeoutSeconds() * 1000L);
        return remainingMillis > callWindowMillis;
    }

    /**
     * Absolute {@link System#nanoTime()} deadline for LLM calls in this phase.
     * {@link #canStartAnotherLlmCall} only guarantees one call window, while a
     * query may retry and back off several times; the deadline keeps those
     * retries inside the phase timeout.
     */
    long phaseDeadlineNanos() {
        int timeoutSeconds = options.phaseBudget().timeoutSeconds();
        if (timeoutSeconds <= 0) {
            return PhaseDeadline.NONE;
        }
        long remainingMillis = Math.max(0L, timeoutSeconds * 1000L - elapsedMillis());
        return PhaseDeadline.fromNow(TimeUnit.MILLISECONDS.toNanos(remainingMillis));
    }

    private long elapsedMillis() {
        return phaseClock.currentTimeMillis() - startMillis;
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
            case "interrupted":
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

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
package org.evosuite.llm.search;

/**
 * Per-context exception-barrier evidence aggregated from a population snapshot
 * (plus optional historical aggregation). Used by the exception-barrier card
 * builder to surface the context (e.g. "arg0=null") that dominates the failures
 * when the method-wide rate is diluted.
 */
final class ExceptionBarrierContextEvidence {
    private final String contextKey;
    private final String contextLabel;
    private int attempts;
    private int successes;
    private int exceptions;
    private String dominantException;
    private String dominantInvocationLabel;
    private int failingInvocationsWithSuccessfulPrefix;
    private final boolean methodLevel;

    ExceptionBarrierContextEvidence(String contextKey,
                                    String contextLabel,
                                    int attempts,
                                    int successes,
                                    int exceptions,
                                    String dominantException,
                                    String dominantInvocationLabel,
                                    int failingInvocationsWithSuccessfulPrefix,
                                    boolean methodLevel) {
        this.contextKey = contextKey;
        this.contextLabel = contextLabel;
        this.attempts = attempts;
        this.successes = successes;
        this.exceptions = exceptions;
        this.dominantException = dominantException == null ? "" : dominantException;
        this.dominantInvocationLabel = dominantInvocationLabel == null ? "" : dominantInvocationLabel;
        this.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
        this.methodLevel = methodLevel;
    }

    void add(int attempts,
             int successes,
             int exceptions,
             String dominantException,
             String dominantInvocationLabel,
             int failingInvocationsWithSuccessfulPrefix) {
        this.attempts += Math.max(0, attempts);
        this.successes += Math.max(0, successes);
        this.exceptions += Math.max(0, exceptions);
        this.failingInvocationsWithSuccessfulPrefix += Math.max(0, failingInvocationsWithSuccessfulPrefix);
        if ((this.dominantException == null || this.dominantException.isEmpty())
                && dominantException != null && !dominantException.isEmpty()) {
            this.dominantException = dominantException;
        }
        if ((this.dominantInvocationLabel == null || this.dominantInvocationLabel.isEmpty())
                && dominantInvocationLabel != null && !dominantInvocationLabel.isEmpty()) {
            this.dominantInvocationLabel = dominantInvocationLabel;
        }
    }

    boolean isMethodLevel() {
        return methodLevel;
    }

    String getContextKey() {
        return contextKey;
    }

    String getContextLabel() {
        return contextLabel;
    }

    int getAttempts() {
        return attempts;
    }

    int getSuccesses() {
        return successes;
    }

    int getExceptions() {
        return exceptions;
    }

    double getBlockage() {
        return attempts <= 0 ? 0.0 : ((double) exceptions / (double) attempts);
    }

    String getDominantException() {
        return dominantException;
    }

    String getDominantInvocationLabel() {
        return dominantInvocationLabel;
    }

    int getFailingInvocationsWithSuccessfulPrefix() {
        return failingInvocationsWithSuccessfulPrefix;
    }

    boolean isStrongerThan(ExceptionBarrierContextEvidence other) {
        if (other == null) {
            return true;
        }
        int exceptionCompare = Integer.compare(exceptions, other.exceptions);
        if (exceptionCompare != 0) {
            return exceptionCompare > 0;
        }
        int blockageCompare = Double.compare(getBlockage(), other.getBlockage());
        if (blockageCompare != 0) {
            return blockageCompare > 0;
        }
        int attemptsCompare = Integer.compare(attempts, other.attempts);
        if (attemptsCompare != 0) {
            return attemptsCompare > 0;
        }
        return contextKey.compareTo(other.contextKey) < 0;
    }
}

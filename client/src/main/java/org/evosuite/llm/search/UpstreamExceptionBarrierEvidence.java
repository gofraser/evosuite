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
 * Upstream exception-barrier evidence: a method invoked <em>before</em> the
 * direct target call repeatedly throws, blocking the target from being reached.
 */
final class UpstreamExceptionBarrierEvidence {
    private final String blockerExecutionKey;
    private String blockerDisplayLabel;
    private int attempts;
    private int successes;
    private int exceptions;
    private String dominantException;
    private String dominantInvocationLabel;
    private int failingInvocationsWithSuccessfulPrefix;

    UpstreamExceptionBarrierEvidence(String blockerExecutionKey,
                                     String blockerDisplayLabel,
                                     int attempts,
                                     int successes,
                                     int exceptions,
                                     String dominantException,
                                     String dominantInvocationLabel,
                                     int failingInvocationsWithSuccessfulPrefix) {
        this.blockerExecutionKey = blockerExecutionKey == null ? "" : blockerExecutionKey;
        this.blockerDisplayLabel = blockerDisplayLabel == null ? "" : blockerDisplayLabel;
        this.attempts = attempts;
        this.successes = successes;
        this.exceptions = exceptions;
        this.dominantException = dominantException == null ? "" : dominantException;
        this.dominantInvocationLabel = dominantInvocationLabel == null ? "" : dominantInvocationLabel;
        this.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
    }

    void add(int attempts,
             int successes,
             int exceptions,
             String dominantException,
             String dominantInvocationLabel,
             int failingInvocationsWithSuccessfulPrefix,
             String blockerDisplayLabel) {
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
        if ((this.blockerDisplayLabel == null || this.blockerDisplayLabel.isEmpty())
                && blockerDisplayLabel != null && !blockerDisplayLabel.isEmpty()) {
            this.blockerDisplayLabel = blockerDisplayLabel;
        }
    }

    String getBlockerExecutionKey() {
        return blockerExecutionKey;
    }

    String getBlockerDisplayLabel() {
        return blockerDisplayLabel;
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

    boolean isStrongerThan(UpstreamExceptionBarrierEvidence other) {
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
        return blockerExecutionKey.compareTo(other.blockerExecutionKey) < 0;
    }
}

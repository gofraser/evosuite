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
 * Per-signature exception-barrier evidence used when one overload of a method
 * dominates failures and the merged method-level rate would otherwise be
 * diluted by the success of another overload.
 */
final class ExceptionBarrierSignatureEvidence {
    private final String signatureKey;
    private final String displayLabel;
    private final int attempts;
    private final int successes;
    private final int exceptions;
    private final String dominantException;
    private final String dominantInvocationLabel;
    private final int failingInvocationsWithSuccessfulPrefix;

    ExceptionBarrierSignatureEvidence(String signatureKey,
                                      String displayLabel,
                                      int attempts,
                                      int successes,
                                      int exceptions,
                                      String dominantException,
                                      String dominantInvocationLabel,
                                      int failingInvocationsWithSuccessfulPrefix) {
        this.signatureKey = signatureKey == null ? "" : signatureKey;
        this.displayLabel = displayLabel == null ? "" : displayLabel;
        this.attempts = attempts;
        this.successes = successes;
        this.exceptions = exceptions;
        this.dominantException = dominantException == null ? "" : dominantException;
        this.dominantInvocationLabel = dominantInvocationLabel == null ? "" : dominantInvocationLabel;
        this.failingInvocationsWithSuccessfulPrefix = failingInvocationsWithSuccessfulPrefix;
    }

    String getSignatureKey() {
        return signatureKey;
    }

    String getDisplayLabel() {
        return displayLabel;
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

    boolean isStrongerThan(ExceptionBarrierSignatureEvidence other) {
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
        return signatureKey.compareTo(other.signatureKey) < 0;
    }
}

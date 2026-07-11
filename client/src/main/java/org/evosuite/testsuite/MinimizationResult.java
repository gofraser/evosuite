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
package org.evosuite.testsuite;

public final class MinimizationResult {

    private final MinimizationStatus status;
    private final MinimizationStopCause underlyingStopCause;
    private final int originalTests;
    private final int originalLength;
    private final int finalTests;
    private final int finalLength;
    private final long elapsedMillis;

    public MinimizationResult(MinimizationStatus status,
                              MinimizationStopCause underlyingStopCause,
                              int originalTests,
                              int originalLength,
                              int finalTests,
                              int finalLength,
                              long elapsedMillis) {
        this.status = status;
        this.underlyingStopCause = underlyingStopCause == null
                ? MinimizationStopCause.NONE
                : underlyingStopCause;
        this.originalTests = originalTests;
        this.originalLength = originalLength;
        this.finalTests = finalTests;
        this.finalLength = finalLength;
        this.elapsedMillis = elapsedMillis;
    }

    public MinimizationStatus getStatus() {
        return status;
    }

    public MinimizationStopCause getUnderlyingStopCause() {
        return underlyingStopCause;
    }

    public int getOriginalTests() {
        return originalTests;
    }

    public int getOriginalLength() {
        return originalLength;
    }

    public int getFinalTests() {
        return finalTests;
    }

    public int getFinalLength() {
        return finalLength;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public boolean isIncomplete() {
        return status != MinimizationStatus.COMPLETED
                && status != MinimizationStatus.DISABLED;
    }

    public static MinimizationResult disabled(TestSuiteChromosome suite) {
        int tests = suite == null ? 0 : suite.size();
        int length = suite == null ? 0 : suite.totalLengthOfTestCases();
        return new MinimizationResult(MinimizationStatus.DISABLED,
                MinimizationStopCause.NONE, tests, length, tests, length, 0L);
    }

    public static MinimizationResult preflightAborted(TestSuiteChromosome suite,
                                                      MinimizationStopCause stopCause) {
        int tests = suite == null ? 0 : suite.size();
        int length = suite == null ? 0 : suite.totalLengthOfTestCases();
        return new MinimizationResult(MinimizationStatus.PREFLIGHT_ABORTED,
                stopCause, tests, length, tests, length, 0L);
    }
}

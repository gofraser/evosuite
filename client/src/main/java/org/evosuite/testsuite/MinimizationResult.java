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

import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;

public final class MinimizationResult {

    private final MinimizationStatus status;
    private final MinimizationStopCause underlyingStopCause;
    private final int originalTests;
    private final int originalLength;
    private final int finalTests;
    private final int finalLength;
    private final long elapsedMillis;

    /**
     * Creates the result of a minimization run.
     *
     * @param status how the minimization ended
     * @param underlyingStopCause the cause that stopped the minimization, or null for none
     * @param originalTests the number of tests before minimization
     * @param originalLength the total number of statements before minimization
     * @param finalTests the number of tests after minimization
     * @param finalLength the total number of statements after minimization
     * @param elapsedMillis the time spent minimizing, in milliseconds
     */
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

    /**
     * Tracks this result as the {@code Minimization_*} runtime variables.
     */
    public void publish() {
        ClientServices.track(RuntimeVariable.Minimization_Status, status.name());
        ClientServices.track(RuntimeVariable.Minimization_Stop_Cause, getUnderlyingStopCause().name());
        ClientServices.track(RuntimeVariable.Minimization_Original_Tests, originalTests);
        ClientServices.track(RuntimeVariable.Minimization_Original_Length, originalLength);
        ClientServices.track(RuntimeVariable.Minimization_Final_Tests, finalTests);
        ClientServices.track(RuntimeVariable.Minimization_Final_Length, finalLength);
        ClientServices.track(RuntimeVariable.Minimization_Elapsed_Millis, elapsedMillis);
    }

    /**
     * Checks whether minimization stopped before it completed, other than by being disabled.
     *
     * @return true if the status is neither completed nor disabled
     */
    public boolean isIncomplete() {
        return status != MinimizationStatus.COMPLETED
                && status != MinimizationStatus.DISABLED;
    }

    /**
     * Creates the result for a suite that was not minimized because minimization is disabled.
     *
     * @param suite the unchanged suite, may be null
     * @return a result with unchanged test and statement counts
     */
    public static MinimizationResult disabled(TestSuiteChromosome suite) {
        int tests = suite == null ? 0 : suite.size();
        int length = suite == null ? 0 : suite.totalLengthOfTestCases();
        return new MinimizationResult(MinimizationStatus.DISABLED,
                MinimizationStopCause.NONE, tests, length, tests, length, 0L);
    }

    /**
     * Creates the result for a suite whose minimization was aborted before it started.
     *
     * @param suite the unchanged suite, may be null
     * @param stopCause the reason the preflight check aborted
     * @return a result with unchanged test and statement counts
     */
    public static MinimizationResult preflightAborted(TestSuiteChromosome suite,
                                                      MinimizationStopCause stopCause) {
        int tests = suite == null ? 0 : suite.size();
        int length = suite == null ? 0 : suite.totalLengthOfTestCases();
        return new MinimizationResult(MinimizationStatus.PREFLIGHT_ABORTED,
                stopCause, tests, length, tests, length, 0L);
    }
}

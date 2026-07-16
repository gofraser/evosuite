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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testsuite;

/** Versioned JSON metadata stored next to a structural-suite serialization. */
public class StructuralSuiteMetadata {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private int schemaVersion;
    private String targetClass;
    private String structuralFingerprint;
    private int testCount;
    private int statementCount;
    private String minimizationStatus;
    private String minimizationStopCause;
    private int minimizationOriginalTests;
    private int minimizationOriginalLength;
    private int minimizationFinalTests;
    private int minimizationFinalLength;
    private long minimizationElapsedMillis;

    public StructuralSuiteMetadata() {
        // Jackson constructor
    }

    static StructuralSuiteMetadata create(String targetClass, TestSuiteChromosome suite,
                                          MinimizationResult minimizationResult) {
        StructuralSuiteMetadata metadata = new StructuralSuiteMetadata();
        metadata.schemaVersion = CURRENT_SCHEMA_VERSION;
        metadata.targetClass = targetClass;
        metadata.structuralFingerprint = StructuralSuiteFingerprint.compute(suite);
        metadata.testCount = suite.size();
        metadata.statementCount = suite.totalLengthOfTestCases();

        MinimizationResult result = minimizationResult == null
                ? MinimizationResult.disabled(suite)
                : minimizationResult;
        metadata.minimizationStatus = result.getStatus().name();
        metadata.minimizationStopCause = result.getUnderlyingStopCause().name();
        metadata.minimizationOriginalTests = result.getOriginalTests();
        metadata.minimizationOriginalLength = result.getOriginalLength();
        metadata.minimizationFinalTests = result.getFinalTests();
        metadata.minimizationFinalLength = result.getFinalLength();
        metadata.minimizationElapsedMillis = result.getElapsedMillis();
        return metadata;
    }

    public MinimizationResult toMinimizationResult() {
        try {
            return new MinimizationResult(
                    MinimizationStatus.valueOf(minimizationStatus),
                    MinimizationStopCause.valueOf(minimizationStopCause),
                    minimizationOriginalTests,
                    minimizationOriginalLength,
                    minimizationFinalTests,
                    minimizationFinalLength,
                    minimizationElapsedMillis);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid minimization metadata", e);
        }
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public void setTargetClass(String targetClass) {
        this.targetClass = targetClass;
    }

    public String getStructuralFingerprint() {
        return structuralFingerprint;
    }

    public void setStructuralFingerprint(String structuralFingerprint) {
        this.structuralFingerprint = structuralFingerprint;
    }

    public int getTestCount() {
        return testCount;
    }

    public void setTestCount(int testCount) {
        this.testCount = testCount;
    }

    public int getStatementCount() {
        return statementCount;
    }

    public void setStatementCount(int statementCount) {
        this.statementCount = statementCount;
    }

    public String getMinimizationStatus() {
        return minimizationStatus;
    }

    public void setMinimizationStatus(String minimizationStatus) {
        this.minimizationStatus = minimizationStatus;
    }

    public String getMinimizationStopCause() {
        return minimizationStopCause;
    }

    public void setMinimizationStopCause(String minimizationStopCause) {
        this.minimizationStopCause = minimizationStopCause;
    }

    public int getMinimizationOriginalTests() {
        return minimizationOriginalTests;
    }

    public void setMinimizationOriginalTests(int minimizationOriginalTests) {
        this.minimizationOriginalTests = minimizationOriginalTests;
    }

    public int getMinimizationOriginalLength() {
        return minimizationOriginalLength;
    }

    public void setMinimizationOriginalLength(int minimizationOriginalLength) {
        this.minimizationOriginalLength = minimizationOriginalLength;
    }

    public int getMinimizationFinalTests() {
        return minimizationFinalTests;
    }

    public void setMinimizationFinalTests(int minimizationFinalTests) {
        this.minimizationFinalTests = minimizationFinalTests;
    }

    public int getMinimizationFinalLength() {
        return minimizationFinalLength;
    }

    public void setMinimizationFinalLength(int minimizationFinalLength) {
        this.minimizationFinalLength = minimizationFinalLength;
    }

    public long getMinimizationElapsedMillis() {
        return minimizationElapsedMillis;
    }

    public void setMinimizationElapsedMillis(long minimizationElapsedMillis) {
        this.minimizationElapsedMillis = minimizationElapsedMillis;
    }
}

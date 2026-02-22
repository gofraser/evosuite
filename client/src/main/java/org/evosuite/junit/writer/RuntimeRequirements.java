/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit.writer;

import org.evosuite.testcase.execution.ExecutionResult;

import java.util.Collections;
import java.util.List;

/**
 * Centralized runtime-related generation decisions derived from configuration
 * and execution results.
 */
public final class RuntimeRequirements {

    private final TestOutputMode outputMode;
    private final boolean useAgent;
    private final boolean hasSecurityException;
    private final boolean usesMocks;
    private final boolean shouldResetProperties;

    private RuntimeRequirements(TestOutputMode outputMode,
                                boolean useAgent,
                                boolean hasSecurityException,
                                boolean usesMocks,
                                boolean shouldResetProperties) {
        this.outputMode = outputMode;
        this.useAgent = useAgent;
        this.hasSecurityException = hasSecurityException;
        this.usesMocks = usesMocks;
        this.shouldResetProperties = shouldResetProperties;
    }

    public static RuntimeRequirements fromResults(List<ExecutionResult> results) {
        List<ExecutionResult> safeResults = results == null ? Collections.emptyList() : results;
        return new RuntimeRequirements(
                TestSuiteWriterUtils.resolveTestOutputMode(),
                TestSuiteWriterUtils.needToUseAgent(),
                TestSuiteWriterUtils.hasAnySecurityException(safeResults),
                TestSuiteWriterUtils.doesUseMocks(safeResults),
                TestSuiteWriterUtils.shouldResetProperties(safeResults)
        );
    }

    public TestOutputMode getOutputMode() {
        return outputMode;
    }

    public boolean isRuntimeEnabled() {
        return outputMode != TestOutputMode.NO_RUNTIME;
    }

    public boolean isScaffoldingFileMode() {
        return outputMode == TestOutputMode.LEGACY_SCAFFOLDING_FILE;
    }

    public boolean isInlineScaffoldingMode() {
        return outputMode == TestOutputMode.LEGACY_INLINE_SCAFFOLDING;
    }

    public boolean isNewExtensionMode() {
        return outputMode == TestOutputMode.NEW_EXTENSION_MODE;
    }

    public boolean needsAgent() {
        return isRuntimeEnabled() && useAgent;
    }

    public boolean hasSecurityException() {
        return hasSecurityException;
    }

    public boolean usesMocks() {
        return usesMocks;
    }

    public boolean shouldResetProperties() {
        return shouldResetProperties;
    }
}

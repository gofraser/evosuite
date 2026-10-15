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
package org.evosuite.junit;

import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Suites written in JUnit 5 extension mode have no scaffolding, so the extension itself has to
 * let a test pass that ends on one of EvoSuite's resource limits.
 */
public class ExtensionModeResourceLimitTest {

    /** Not named like a test, so that surefire does not run it on its own. */
    @EvoRunnerParameters
    public static class LiftFixture {
        @RegisterExtension
        static EvoSuiteExtension runner = new EvoSuiteExtension(LiftFixture.class);

        @Test
        public void endsOnLimit() {
            throw new TooManyResourcesException("Loop has been executed more times than the allowed 10000");
        }

        @EvoSuiteEndsOnResourceLimit
        @Test
        public void markedEndsOnStackOverflow() {
            throw new StackOverflowError();
        }

        @Test
        public void unmarkedEndsOnStackOverflow() {
            throw new StackOverflowError();
        }

        @Test
        public void failsOtherwise() {
            throw new IllegalStateException();
        }
    }

    private String className;
    private boolean resetStaticState;
    private boolean mockJVMNonDeterminism;
    private boolean mockGUI;
    private boolean useVFS;
    private boolean useVNET;
    private boolean useSeparateClassLoader;
    private boolean useJEE;
    private int maxNumberOfThreads;
    private long maxNumberOfIterationsPerLoop;

    @BeforeEach
    public void saveRuntimeSettings() {
        className = RuntimeSettings.className;
        resetStaticState = RuntimeSettings.resetStaticState;
        mockJVMNonDeterminism = RuntimeSettings.mockJVMNonDeterminism;
        mockGUI = RuntimeSettings.mockGUI;
        useVFS = RuntimeSettings.useVFS;
        useVNET = RuntimeSettings.useVNET;
        useSeparateClassLoader = RuntimeSettings.useSeparateClassLoader;
        useJEE = RuntimeSettings.useJEE;
        maxNumberOfThreads = RuntimeSettings.maxNumberOfThreads;
        maxNumberOfIterationsPerLoop = RuntimeSettings.maxNumberOfIterationsPerLoop;
    }

    @AfterEach
    public void restoreRuntimeSettings() {
        RuntimeSettings.className = className;
        RuntimeSettings.resetStaticState = resetStaticState;
        RuntimeSettings.mockJVMNonDeterminism = mockJVMNonDeterminism;
        RuntimeSettings.mockGUI = mockGUI;
        RuntimeSettings.useVFS = useVFS;
        RuntimeSettings.useVNET = useVNET;
        RuntimeSettings.useSeparateClassLoader = useSeparateClassLoader;
        RuntimeSettings.useJEE = useJEE;
        RuntimeSettings.maxNumberOfThreads = maxNumberOfThreads;
        RuntimeSettings.maxNumberOfIterationsPerLoop = maxNumberOfIterationsPerLoop;
    }

    @Test
    public void onlyTheLimitAndAMarkedStackOverflowPass() {
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(LiftFixture.class)).build(), listener);

        TestExecutionSummary summary = listener.getSummary();
        assertEquals(4, summary.getTestsStartedCount());
        assertEquals(2, summary.getTestsSucceededCount());
        assertEquals(2, summary.getTestsFailedCount());
    }
}

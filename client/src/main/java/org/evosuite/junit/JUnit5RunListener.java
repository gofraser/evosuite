/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit;

import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.utils.LoggingUtils;
import org.junit.internal.Throwables;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JUnit5RunListener implements TestExecutionListener {
    private static final Logger logger = LoggerFactory.getLogger(JUnit5RunListener.class);
    private final JUnitRunner junitRunner;


    private JUnitResult testResult = null;


    private long start;

    public JUnit5RunListener(JUnitRunner junitRunner) {
        this.junitRunner = junitRunner;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        LoggingUtils.getEvoLogger().info("* Number of test cases to execute: "
                + testPlan.countTestIdentifiers(ignored -> true));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        LoggingUtils.getEvoLogger().info("* Number of test cases executed: No information available");
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        LoggingUtils.getEvoLogger().info("* Ignored: " + "ClassName: "
                + testIdentifier.getDisplayName() + ", Reason: " + reason);
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!testIdentifier.isTest()) {
            return;
        }

        LoggingUtils.getEvoLogger().info("* Started: " + "ClassName: " + testIdentifier.getDisplayName());

        this.start = System.nanoTime();

        this.testResult = new JUnitResult(testIdentifier.getDisplayName(), this.junitRunner.getJUnitClass());
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) {
            return;
        }
        LoggingUtils.getEvoLogger().info("* Finished: " + "ClassName: " + testIdentifier.getDisplayName());
        this.testResult.setRuntime(System.nanoTime() - this.start);
        this.testResult.incrementRunCount();
        this.testResult.setExecutionTrace(ExecutionTracer.getExecutionTracer().getTrace());
        ExecutionTracer.getExecutionTracer().clear();
        if (testExecutionResult.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            this.junitRunner.addResult(this.testResult);
        } else if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {

            Throwable throwable = testExecutionResult.getThrowable().orElse(null);
            if (throwable != null) {
                for (StackTraceElement s : throwable.getStackTrace()) {
                    LoggingUtils.getEvoLogger().info("   " + s.toString());
                }
                this.testResult.setTrace(Throwables.getStacktrace(throwable));
            }

            this.testResult.setSuccessful(false);
            this.testResult.incrementFailureCount();
            this.junitRunner.addResult(this.testResult);
        } else {
            // ABORTED (eg failed assumptions) must still be recorded to avoid dropping tests.
            Throwable throwable = testExecutionResult.getThrowable().orElse(null);
            if (throwable != null) {
                for (StackTraceElement s : throwable.getStackTrace()) {
                    LoggingUtils.getEvoLogger().info("   " + s.toString());
                }
                this.testResult.setTrace(Throwables.getStacktrace(throwable));
            }
            this.testResult.setSuccessful(false);
            this.junitRunner.addResult(this.testResult);
        }
    }

    @Override
    public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
    }
}

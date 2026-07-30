/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCaseExecutorTimeoutRecoveryTest {

    @Test
    public void recognizesGroovyDynamicBootstrapWithoutMatchingApplicationCode() {
        StackTraceElement[] bootstrap = {
                new StackTraceElement("org.codehaus.groovy.vmplugin.v8.CacheableCallSite",
                        "getAndPut", "CacheableCallSite.java", 74),
                new StackTraceElement("groovy.text.markup.MarkupTemplateEngine",
                        "<init>", "MarkupTemplateEngine.java", 94)
        };
        StackTraceElement[] application = {
                new StackTraceElement("example.MyGroovyApplication",
                        "render", "MyGroovyApplication.groovy", 12)
        };

        assertTrue(TestCaseExecutor.isRuntimeBootstrapStack(bootstrap));
        assertFalse(TestCaseExecutor.isRuntimeBootstrapStack(application));
    }

    @Test
    public void liveWorkerStillRequiresCleanupAfterFutureCancellation() {
        assertTrue(TestCaseExecutor.shouldForceStopAfterCancellation(false, true));
        assertTrue(TestCaseExecutor.shouldForceStopAfterCancellation(true, false));
        assertFalse(TestCaseExecutor.shouldForceStopAfterCancellation(false, false));
    }

    @Test
    public void eitherRotationLimitStopsReplacementExecutors() {
        assertTrue(TestCaseExecutor.reachedExecutorRotationLimit(50, 0, 50, 10));
        assertTrue(TestCaseExecutor.reachedExecutorRotationLimit(1, 10, 50, 10));
        assertFalse(TestCaseExecutor.reachedExecutorRotationLimit(1, 1, 50, 10));
    }
}
